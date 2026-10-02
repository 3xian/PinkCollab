use super::*;

pub(super) enum ModelSetting {
    Model { provider: String, model_id: String },
    FastMode(bool),
    ThinkingLevel(String),
}

impl SessionController {
    pub(super) async fn apply_model_setting(
        &self,
        receipt: &mut OperationRecord,
        generation: &str,
        setting: ModelSetting,
    ) -> std::result::Result<CommandResult, CommandFailure> {
        let (_, runtime) = self.dispatchable_runtime(generation).await?;
        self.bind_generation(receipt, generation).await?;
        let (request, applied) = match setting {
            ModelSetting::Model { provider, model_id } => (
                json!({"type":"set_model","provider":provider,"modelId":model_id}),
                "Model set",
            ),
            ModelSetting::FastMode(enabled) => (
                json!({"type":"set_fast_mode","enabled":enabled}),
                "Fast mode set",
            ),
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
                (
                    json!({"type":"set_thinking_level","level":level}),
                    "Thinking level set",
                )
            }
        };
        runtime.request(request).await.map_err(rpc_failure)?;
        self.refresh_state(generation, &runtime)
            .await
            .map_err(|err| {
                CommandFailure::uncertain(format!("{applied}; state refresh failed: {err}"))
            })?;
        Ok(CommandResult::Succeeded(json!({"generation":generation})))
    }
}
