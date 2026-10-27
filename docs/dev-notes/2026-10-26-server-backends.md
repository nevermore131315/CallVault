# Server (API) backends — transcribe and summarise on a home server

Date: 2026-10-26 · Branch: `feat/server-backends`

## What it is

Either heavy job — whisper transcription and the llama.cpp summary — can be handed to a server on
the user's own network instead of the phone's CPU. Two independent switches in Settings
(transcription gets one, summaries gets one), each with its own base URL, optional bearer key,
and — for summaries — an optional model name.

**Leaving nothing on the phone that does not have to:**

- Transcription (`RemoteTranscriber`): the phone still decodes the recording — the channel-energy
  speaker labeller (issue #38) rides that decode and nothing on the network carries channel
  separation — then uploads the **original file** (a few MB of Opus, not a dozen times that in
  WAV) to `POST {base}/v1/audio/transcriptions?output=json_segments`.
- Summaries (`RemoteSummaryHost`): a `SummaryModelHost` that answers the existing prompts with
  `POST {base}/v1/chat/completions`. The model is **whatever the server already has loaded**:
  the name is resolved once per run from llama-swap's `GET /running`, falling back to
  `GET /v1/models`; typing a name pins it instead. The 2.6 GB summariser never touches the phone.

## Server contract (what a backend must speak)

- ASR: OpenAI `POST /v1/audio/transcriptions` multipart (`file` part; containers decoded by the
  server). CallVault requests `?output=json_segments` and accepts, in order of preference:
  `{"segments":[{start_ms,end_ms,text}]}` (exact ms), OpenAI `verbose_json` seconds, or plain
  `{"text":…}` — which becomes one segment across the recording, keeping every word and losing
  only tap-to-seek inside that stretch.
- LLM: OpenAI `POST /v1/chat/completions`. Extra fields CallVault sends and honest servers
  tolerate: `grammar` (llama.cpp GBNF; **servers may ignore it** — so output is run through
  `extractJson`, one fenced/preamble-tolerant retry, and `CallSummary.parse`), and
  `chat_template_kwargs.enable_thinking=false` (reasoning models that answer only in
  `reasoning_content` are still read).
- Auth: `Authorization: Bearer <key>` when a key is set; `http` is allowed via
  `res/xml/network_security_config.xml` because everything CallVault reaches on its own is https
  and a LAN server URL is typed by the user's own hands.

## Nevermore reference implementation

- ASR: the Phonon-2 bridge (`~/transcribe-server/phonon_bridge.py`, `qwen-asr.service` :8098)
  gained `output=json_segments`: ffmpeg to 16 kHz PCM → numpy energy VAD slicing at turn-level
  silences (≥0.9 s below −38 dBFS, 0.15 s pad, ≤180 s per slice) → one warm-fermion forward per
  slice → `{"text","segments"}`. The default `{"text":…}` contract is byte-identical for
  Vocalinux/FUTO; slicing failure falls through to whole-file transcription. Verified
  end-to-end 2026-10-26 with a 21 s two-utterance Opus file: two segments at exactly the silence
  boundaries.
- LLM: llama-swap (:8091) — `/running` answers "what is loaded", so "summarise with the current
  model" needs zero configuration. Measured caveat: the Strata engine ignores `grammar`
  (returned `Yes` to a `"yes"|"no"` grammar), which is exactly why the parse-tolerant path exists.

## Phone-side invariants kept

- Stop routes unchanged: `TranscriptionEngine.requestAbort()` and `SummaryEngine.requestAbort()`
  also abort the remote runs, so every existing Stop covers both backends without knowing them.
- Busy gates (`SummaryQueue`, `SummaryWorker` defer, HomeViewModel waveform pause) OR in the
  remote `isRunning` flags.
- Model-missing gates (Home screen, import follow-up, summaries group subtitle) pass when a
  server is configured — nothing has to be installed for a server job.
- Settings export carries the switches/URLs/keys/model (they are user preferences, not
  per-device state); the wizard still offers local downloads.

## Test-harness note (not fork-specific)

On Linux every Robolectric test previously died at application setup with
`UnsatisfiedLinkError: no conscrypt_jni` (upstream recorded the same in
`2026-10-20-fork-kabasik007-hardening.md`, with the openjdk-uber swap as the answer). Applied
`testRuntimeOnly(conscrypt-openjdk-uber)` + excluding `conscrypt-android` from
`testRuntimeClasspath` — desktop tests run, Android runtime unchanged.

New tests: `app/src/test/java/com/baba/callvault/remote/ServerBackendsParsingTest.kt` —
segment-shape tolerance, text fallback, fence/thinking stripping, escape-aware brace matching,
`content` vs `reasoning_content`.
