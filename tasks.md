# Task List: Voice Assistant Core & NLU Overhaul

- [x] 1. Phone call safety fix: enforce explicit call verb, block emergency numbers, guard against accidental dials in AgentCore, PostClassificationValidator, ContactHelper & PhoneTool <!-- id: 0 -->
- [x] 2. YouTube autoplay via web scraping & direct link in browser with autoplay=1 in MediaControlTool <!-- id: 1 -->
- [x] 3. Notes update & delete support in Dao, NotesTool, and AgentCore <!-- id: 2 -->
- [x] 4. Extended reminders supporting days, weeks, months in AgentCore & ReminderScheduler with reboot persistence <!-- id: 3 -->
- [x] 5. Fix Google search queries falling back to offline mode in AgentCore & PostClassificationValidator <!-- id: 4 -->
- [x] 6. WhatsApp double-send fix in FridayAccessibilityService (prevent secondary tap on voice memo button) <!-- id: 5 -->
- [x] 7. Speech recognition & contact name matching accuracy overhaul in ContactHelper & SpeechToTextHelper <!-- id: 6 -->
- [x] 8. Build a dedicated local PC test harness for voice recognition, transcript correction & contact matching (`scripts/test_voice_contact_harness.py`) <!-- id: 7 -->
- [x] 9. Update Joint NLU training pipeline & Colab notebook (`scripts/train_joint_nlu.py`, `scripts/friday_joint_nlu_training.ipynb`) with notes_update, negative call examples, extended reminders <!-- id: 8 -->
- [x] 10. Code-level compile sanity verified <!-- id: 9 -->
- [x] 11. Update `context.md` with latest architecture & context <!-- id: 10 -->
