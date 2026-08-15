# Task List: Android Joint NLU Integration & Lightweight LLM Upgrade

- [x] 1. Copy Joint NLU ONNX model (`joint_nlu_model.onnx`), intent labels (`joint_intent_labels.json`), slot labels (`joint_slot_labels.json`), and vocabulary to Android assets. <!-- id: 0 -->
- [x] 2. Update `WordpieceTokenizer.kt` to support subword-to-string token decoding (`convertTokensToString`). <!-- id: 1 -->
- [x] 3. Update `NluIntentClassifier.kt` to load dual-head ONNX outputs (`intent_logits` + `slot_logits`), decode neural BIO slots, and return `JointNluResult`. <!-- id: 2 -->
- [x] 4. Update `AgentCore.kt` dispatch loop to use neural slot parameters (WhatsApp, Calls, Navigation, Notes, Memory Recall, App Launch) and modularize into domain handlers. <!-- id: 3 -->
- [x] 5. Update `ModelManager.kt` with `joint_nlu_model.onnx` and Qwen-2.5-0.5B-Instruct-GGUF (Q4_K_M) download configuration. <!-- id: 4 -->
- [x] 6. Verify Android build and update `context.md`. <!-- id: 5 -->

