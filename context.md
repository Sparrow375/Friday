# Project Friday: Personal Android Voice Assistant

## Overview
Friday is a highly personalized Android-based voice assistant designed for a single user's phone. It aims to combine local voice commands, speaker recognition (security), custom routines, and LLM-powered capabilities into a seamless, modern application.

## Key Goals
1. **Personalization & Routines**: Tailored features and routines specific to the user.
2. **Speaker Verification**: Security feature to ensure the app only responds to the user's voice (voice profile enrollment by speaking a short sample).
3. **Local/Offline Command Classification**: Efficiently parse and execute pre-built commands locally without querying the LLM, falling back to the LLM only when necessary.
4. **High-Quality Voice Recognition**: Fast and accurate speech-to-text.
5. **Modern Android Design**: Premium look and feel with smooth interactions.

## Architecture
- **Front-end**: Android Application.
- **Voice Recognition (Speech-to-Text)**: Local (e.g., Vosk, Whisper Mobile, or Android SpeechRecognizer) or cloud-assisted.
- **Speaker Recognition (Voice Bio-metrics)**: Local feature extractor and classifier to verify if the speaker is the owner.
- **Local Command Classifier**: Regex, rule-based, or light NLP model to categorize commands and trigger local actions.
- **LLM Engine**: API integration (e.g., Gemini API) for conversational queries and complex intent resolving.

## Workspace Structure
- `f:/Avaneesh/projects/Friday/`
  - `.git`
  - `prompt.md` (User request details)
  - `context.md` (This context file)
