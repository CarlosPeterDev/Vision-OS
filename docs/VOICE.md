# Voice engine

## ElevenLabs APIs used (checked against the official docs, November 2026)
- TTS: `POST /v1/text-to-speech/{voice_id}/stream`, `output_format=pcm_24000`, one request per sentence.
- STT: `wss /v1/speech-to-text/realtime`, `pcm_16000`, `commit_strategy=vad`, `input_audio_chunk` messages.
- `GET /v2/voices`, `GET /v1/models`, `GET /v1/user/subscription`.
- Docs: https://elevenlabs.io/docs

Defaults: `eleven_flash_v2_5` and `scribe_v2_realtime`, both editable. `scribe_v3_live` is selectable.

## Behaviour
- Cloud is optional. STT and TTS fall back to Android independently.
- Bad key or exhausted quota: cloud stays off until the key or voice settings change, or the app restarts.
- Rate limit and server errors: 30 s pause. Network and concurrency errors: 5 s.
- A partly played sentence is not repeated. A recognition that fails mid-sentence asks the user to retry.
- Usage: the server quota comes from the subscription endpoint (a restricted key needs permission to read it).
  Session counters in the app are local estimates, not billing figures.

## Limits
- This is not full-duplex. The app listens, then speaks; during speech only a local detector listens for interruption.
- The detector measures loudness, so loud noises can interrupt. Voice interruption needs hardware echo cancellation;
  how well it works varies by phone, and must be tested.
- The Android fallback recognizer may be online or offline and controls its own microphone, so the first
  words of an interruption can be lost with it.
- "Auto" language uses Scribe language detection; Android uses the device's default language.
- The user's own key is used directly from the phone. Use a key restricted to the needed features with a credit limit.
- No conversation history is kept, so each AI turn is independent.
- AI endpoints on plain HTTP (such as a LAN Ollama server) are not supported yet.

## Device checklist (none verified yet)
- [ ] Arabic and English: Scribe, ElevenLabs TTS, Android fallback
- [ ] Barge-in on speaker, earpiece and headset; no self-interruption
- [ ] Wrong key, exhausted quota, airplane mode
- [ ] Key survives restart; delete works
- [ ] Audio focus with music or a call; leaving the app releases the microphone
- [ ] Camera preview, permission denial, camera taken by another app
