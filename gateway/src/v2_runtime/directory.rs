use super::*;

impl SessionDirectory {
    pub fn new(
        store: Arc<Store>,
        browser: Arc<Browser>,
        bus: Arc<Bus>,
        executable: String,
        args: Vec<String>,
        max: usize,
    ) -> Arc<Self> {
        Arc::new(Self {
            controllers: Mutex::new(HashMap::new()),
            store,
            browser,
            bus,
            quota: Arc::new(Semaphore::new(max)),
            executable,
            args,
        })
    }
    async fn controller(&self, id: &str) -> Result<Option<Arc<SessionController>>> {
        if let Some(existing) = self.active_controller(id).await {
            return Ok(Some(existing));
        }
        let session_id = id.to_owned();
        let Some(session) = self
            .store
            .run(move |store| store.v2_session(&session_id))
            .await?
        else {
            return Ok(None);
        };
        let mut controllers = self.controllers.lock().await;
        controllers.retain(|_, weak| weak.strong_count() > 0);
        if let Some(existing) = controllers.get(id).and_then(std::sync::Weak::upgrade) {
            return Ok(Some(existing));
        }
        let controller = Arc::new(SessionController {
            state: Mutex::new(ControllerState {
                session,
                runtime: None,
                projection: None,
                messages: Vec::new(),
                finalized_messages: HashSet::new(),
                pending_prompt_results: HashMap::new(),
                settled_revision: 0,
                dirty_messages: HashSet::new(),
                removed_messages: Vec::new(),
                display_flush_scheduled: false,
            }),
            ordinary_dispatch: Mutex::new(()),
            prompt_interrupt_admission: Arc::new(Mutex::new(())),
            store: self.store.clone(),
            browser: self.browser.clone(),
            bus: self.bus.clone(),
            quota: self.quota.clone(),
            executable: self.executable.clone(),
            args: self.args.clone(),
        });
        controllers.insert(id.into(), Arc::downgrade(&controller));
        Ok(Some(controller))
    }
    async fn active_controller(&self, id: &str) -> Option<Arc<SessionController>> {
        let mut controllers = self.controllers.lock().await;
        let controller = controllers.get(id).and_then(std::sync::Weak::upgrade);
        if controller.is_none() {
            controllers.remove(id);
        }
        controller
    }
    pub async fn view(&self, id: &str) -> Result<Option<SessionView>> {
        if let Some(controller) = self.active_controller(id).await {
            return Ok(Some(controller.view().await?));
        }
        let session_id = id.to_owned();
        self.store
            .run(move |store| {
                let Some(session) = store.v2_session(&session_id)? else {
                    return Ok(None);
                };
                let recent_operations = store.recent_operations(&session_id, 20)?;
                let history_ref = session
                    .engine_session_ref
                    .as_ref()
                    .map(|_| "omp".to_string());
                Ok(Some(SessionView {
                    session,
                    runtime: None,
                    recent_operations,
                    messages: Vec::new(),
                    history_ref,
                }))
            })
            .await
    }
    pub async fn list(&self) -> Result<Vec<Value>> {
        let sessions = self.store.run(|store| store.v2_sessions()).await?;
        self.summaries(sessions).await
    }
    pub async fn list_page(
        &self,
        after: Option<(&str, &str)>,
        limit: usize,
    ) -> Result<(Vec<Value>, bool)> {
        let after = after.map(|(created, id)| (created.to_owned(), id.to_owned()));
        let (sessions, has_more) = self
            .store
            .run(move |store| {
                store.v2_sessions_page(
                    after
                        .as_ref()
                        .map(|(created, id)| (created.as_str(), id.as_str())),
                    limit,
                )
            })
            .await?;
        Ok((self.summaries(sessions).await?, has_more))
    }
    async fn summaries(&self, sessions: Vec<SessionRecord>) -> Result<Vec<Value>> {
        let controllers = {
            let mut controllers = self.controllers.lock().await;
            controllers.retain(|_, weak| weak.strong_count() > 0);
            controllers
                .iter()
                .filter_map(|(id, weak)| weak.upgrade().map(|controller| (id.clone(), controller)))
                .collect::<HashMap<_, _>>()
        };
        let mut result = Vec::with_capacity(sessions.len());
        for session in sessions {
            let runtime = if let Some(controller) = controllers.get(&session.id) {
                controller.state.lock().await.projection.clone()
            } else {
                None
            };
            result.push(json!({"session":session,"runtime":runtime}));
        }
        Ok(result)
    }
    pub async fn submit(
        self: &Arc<Self>,
        client_id: String,
        session_id: String,
        command_id: String,
        command: Command,
    ) -> std::result::Result<Submitted, SubmitError> {
        if command_id.is_empty()
            || command_id.len() > 128
            || !command_id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
        {
            return Err(SubmitError::Invalid("Valid commandId required".into()));
        }
        command
            .validate()
            .map_err(|err| SubmitError::Invalid(err.to_string()))?;
        let controller = self
            .controller(&session_id)
            .await
            .map_err(|_| SubmitError::Persistence)?
            .ok_or(SubmitError::NotFound)?;
        // Preserve admission order until a prompt or abort has reached OMP's input pipe.
        // Stop bypasses this gate so a blocked prompt write cannot delay termination.
        let admission = if matches!(command, Command::Prompt { .. } | Command::Interrupt { .. }) {
            Some(
                controller
                    .prompt_interrupt_admission
                    .clone()
                    .lock_owned()
                    .await,
            )
        } else {
            None
        };
        let fingerprint = hex::encode(Sha256::digest(
            serde_json::to_vec(&command)
                .map_err(|_| SubmitError::Invalid("Invalid command".into()))?,
        ));
        let now = Utc::now();
        let receipt = OperationRecord {
            command_id: command_id.clone(),
            client_id: client_id.clone(),
            session_id: session_id.clone(),
            command_type: command.name().into(),
            request_fingerprint: fingerprint.clone(),
            runtime_generation: None,
            status: "accepted".into(),
            result: None,
            error: None,
            created_at: now,
            updated_at: now,
        };
        let stored_receipt = receipt.clone();
        let inserted = self
            .store
            .run(move |store| store.insert_operation(&stored_receipt))
            .await;
        match inserted {
            Ok(false) => {
                let previous = self
                    .operation(&client_id, &session_id, &command_id)
                    .await
                    .map_err(|_| SubmitError::Persistence)?
                    .ok_or(SubmitError::Persistence)?;
                if previous.request_fingerprint != fingerprint {
                    return Err(SubmitError::Conflict);
                }
                Ok(Submitted {
                    receipt: previous,
                    replayed: true,
                    receipt_stored: true,
                })
            }
            Ok(true) => {
                let stop = matches!(command, Command::StopRuntime { .. });
                tokio::spawn(async move {
                    controller
                        .execute(receipt.clone(), command, stop, admission)
                        .await;
                });
                // Read from storage because the spawned task may already have advanced it.
                let current = self
                    .operation(&client_id, &session_id, &command_id)
                    .await
                    .map_err(|_| SubmitError::Persistence)?
                    .ok_or(SubmitError::Persistence)?;
                Ok(Submitted {
                    receipt: current,
                    replayed: false,
                    receipt_stored: true,
                })
            }
            Err(_) if matches!(command, Command::StopRuntime { .. }) => {
                let copy = receipt.clone();
                tokio::spawn(async move {
                    controller.execute(copy, command, true, admission).await;
                });
                Ok(Submitted {
                    receipt,
                    replayed: false,
                    receipt_stored: false,
                })
            }
            Err(_) => Err(SubmitError::Persistence),
        }
    }
    pub async fn operation(
        &self,
        client_id: &str,
        session_id: &str,
        command_id: &str,
    ) -> Result<Option<OperationRecord>> {
        let client_id = client_id.to_owned();
        let session_id = session_id.to_owned();
        let command_id = command_id.to_owned();
        self.store
            .run(move |store| store.operation(&client_id, &session_id, &command_id))
            .await
    }
    pub async fn models(&self, id: &str) -> Result<(Vec<ModelInfo>, Vec<String>)> {
        let controller = self
            .active_controller(id)
            .await
            .context("runtime required")?;
        let runtime = {
            controller
                .state
                .lock()
                .await
                .runtime
                .as_ref()
                .map(|r| r.process.clone())
                .context("runtime required")?
        };
        let response = runtime
            .request(json!({"type":"get_available_models"}))
            .await?;
        let models = response["data"]["models"]
            .as_array()
            .context("OMP returned no model list")?
            .iter()
            .map(|value| {
                model_info(&json!({"model":value})).context("OMP returned an invalid model")
            })
            .collect::<Result<Vec<_>>>()?;
        let response = runtime
            .request(json!({"type":"get_available_thinking_levels"}))
            .await?;
        let levels = response["data"]["levels"]
            .as_array()
            .context("OMP returned no thinking levels")?
            .iter()
            .filter_map(|value| value.as_str().map(str::to_owned))
            .collect();
        Ok((models, levels))
    }
    pub async fn close(&self) {
        let controllers = self
            .controllers
            .lock()
            .await
            .values()
            .filter_map(std::sync::Weak::upgrade)
            .collect::<Vec<_>>();
        futures_util::future::join_all(
            controllers
                .iter()
                .map(|controller| controller.stop_on_shutdown()),
        )
        .await;
    }
}
