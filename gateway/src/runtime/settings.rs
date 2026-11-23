use super::*;

pub(super) enum ModelSetting {
    Model { provider: String, model_id: String },
    FastMode(bool),
    ThinkingLevel(String),
}

impl SessionController {
    pub(super) async fn apply_model_settings(
        &self,
        receipt: &mut OperationRecord,
        generation: &str,
        settings: impl IntoIterator<Item = ModelSetting>,
    ) -> std::result::Result<CommandResult, CommandFailure> {
        let (_, runtime) = self.dispatchable_runtime(generation).await?;
        self.bind_generation(receipt, generation).await?;
        let mut changed = false;
        for setting in settings {
            if let Err(error) = setting.apply(&runtime).await {
                if changed {
                    self.refresh_state(generation, &runtime)
                        .await
                        .map_err(|err| {
                            CommandFailure::uncertain(format!(
                                "Settings partially applied; state refresh failed: {err}"
                            ))
                        })?;
                    return Err(if error.uncertain {
                        error
                    } else {
                        CommandFailure::failed("settings_partially_applied", error.message)
                    });
                }
                return Err(error);
            }
            changed = true;
        }
        self.refresh_state(generation, &runtime)
            .await
            .map_err(|err| {
                CommandFailure::uncertain(format!(
                    "Model settings applied; state refresh failed: {err}"
                ))
            })?;
        Ok(CommandResult::Succeeded(json!({"generation":generation})))
    }
}

impl ModelSetting {
    async fn apply(self, runtime: &Runtime) -> std::result::Result<(), CommandFailure> {
        let request = match self {
            Self::Model { provider, model_id } => {
                json!({"type":"set_model","provider":provider,"modelId":model_id})
            }
            Self::FastMode(enabled) => json!({"type":"set_fast_mode","enabled":enabled}),
            ModelSetting::ThinkingLevel(level) => {
                let available = runtime
                    .request(json!({"type":"get_available_thinking_levels"}))
                    .await
                    .map_err(rpc_failure)?;
                let levels = available["data"]["levels"].as_array().ok_or_else(|| {
                    CommandFailure::failed(
                        "unsupported_capability",
                        "OMP did not return thinking levels",
                    )
                })?;
                if !levels.iter().any(|candidate| {
                    candidate.as_str() == Some(level.as_str())
                        || candidate["id"].as_str() == Some(level.as_str())
                }) {
                    return Err(CommandFailure::failed(
                        "unsupported_capability",
                        "Thinking level is unavailable",
                    ));
                }
                json!({"type":"set_thinking_level","level":level})
            }
        };
        runtime.request(request).await.map_err(rpc_failure)?;
        Ok(())
    }
}
