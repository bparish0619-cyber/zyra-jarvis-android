# Zyra (Android client for OpenJarvis)

Native Kotlin Android app for talking to Zyra, the OpenJarvis backend
running on Brandon's UGREEN NAS.

- Package: `net.northbrand.zyrajarvis`, app name "Zyra"
- One Activity, no WebView, no third-party libraries (raw
  HttpURLConnection + org.json)
- Animated Zyra stage (idle / listening / thinking / speaking / happy /
  playful / sleepy portraits with crossfades and motion loops)
- Typed chat, push-to-talk, and a truly handsfree two-way voice mode
  (listen, transcribe, chat, speak, listen again; half-duplex)
- Replies are spoken aloud via the backend's TTS
- v0.2 UI speaks the OpenJarvis web frontend's design language:
  glass identity bar with a live backend health dot, mono HUD status
  strip (STATE / ENGINE / MODE), the stage as a bordered panel whose
  frame glows cyan while listening/speaking, ChatPage-style messages
  (assistant = labeled plain text, user = translucent blue bubble).
  All colors live in `res/values/colors.xml`, the JARVIS THEME BLOCK

## Backend

Default base URL: `http://192.168.1.60:8000` (editable in the SETUP
panel behind the identity bar). Endpoints used:

- `POST /v1/chat/completions` — OpenAI-shaped chat
- `POST /v1/speech/transcribe` — multipart audio upload
- `POST /v1/speech/synthesize` — returns WAV audio

**Auth:** the server binds `0.0.0.0`, and OpenJarvis requires an API key
on every `/v1/*` route in that setup. Enter the same value as the NAS
project's `OPENJARVIS_API_KEY` in the app's "Backend API key" settings
field; the app sends it as `Authorization: Bearer <key>`.

## Building

Builds run in GitHub Actions only (`.github/workflows/build-debug.yml`):
push to `main` or dispatch the workflow, then download the
`ZyraJarvis-debug` artifact (a debug-signed APK that installs directly).

## Layout

- `app/src/main/java/net/northbrand/zyrajarvis/MainActivity.kt` — the
  whole app: UI, stage state machine, networking, voice, handsfree loop
- `app/src/main/res/drawable-nodpi/zyra_*.webp` — the stage portraits
- `app/src/main/res/layout/activity_main.xml` — the screen
