<div align="center">

# WaveKey

### Your voice never leaves the phone.

An Android keyboard that transcribes speech, cleans it up and rewrites it —
with two AI models running **entirely on the device**. **Free, with no subscription
and no minute counter.** No account, no API key, no server, and no network
permission in the keyboard process at all.

<img src="docs/screenshots/wavekey-demo.gif" alt="WaveKey in use: the strip becomes the voice bar, the rail moves with the voice, and the dictated sentence is cleaned up" width="300">
<img src="docs/screenshots/wavekey.jpg" alt="WaveKey mid-dictation on a real phone: the strip has become the voice bar and says Cleaning up, with the dictated question already in the field" width="300">

<sub>The flow, and the same thing on a real phone.</sub>

### [**See it in motion → nvkudva.github.io/WaveKey**](https://nvkudva.github.io/WaveKey/)

[![Website](https://img.shields.io/badge/website-live-A855F7)](https://nvkudva.github.io/WaveKey/)
[![Price](https://img.shields.io/badge/price-free%20forever-22D3EE)](#free-and-free-of-a-meter)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84)](#requirements)
[![ASR](https://img.shields.io/badge/ASR-Parakeet%20TDT%200.6B-A855F7)](#the-two-models)
[![LLM](https://img.shields.io/badge/LLM-Qwen3%200.6B-22D3EE)](#the-two-models)
[![Offline](https://img.shields.io/badge/network-never-FB7185)](#privacy-is-the-architecture)

</div>

---

## What it does

| | |
|---|---|
| 💸 **Free, and free of a meter** | No subscription, no trial, no minutes to run out of, no tier that unlocks the better model. There is no server to bill you for. |
| 🎙️ **Dictate and edit at the same time** | The mic stays live while text lands. A pause ends the *sentence*, not the session — so you can speak, watch it commit, fix a word with your thumb, and keep speaking. |
| 🧠 **Two models, both on-device** | **Parakeet TDT 0.6B** turns speech into words. **Qwen 3 0.6B** turns those words into writing. Neither one leaves the phone. |
| ✨ **AI fix, always within reach** | One key runs the deterministic rules and then the LLM over what you just wrote — spelling, spacing, casing, clumsy phrasing. Press it again to undo. |
| ⌨️ **Continuous fixing as you type** | Finished sentences are quietly rescored against the words the decoder ranked second, and the swap is shown to you rather than slipped past you. |
| 🔒 **Private by construction** | The keyboard process has no `INTERNET` component. A build-time test fails if one ever appears. |
| 📋 **A clipboard that reads like a list** | Typed clips — link, image, phone, text — each with a glyph, a size or a host, and how long it has left. |

---

## Free, and free of a meter

Dictation services charge monthly because they run your voice through their
hardware. WaveKey has no hardware to pay for: both models run on your phone, so an
hour of dictation costs the battery it takes and nothing else. No account, no trial
that expires, no cap.

It is also free in the other sense — GPL-3.0, so every line between your microphone
and your text field can be read, audited and forked.

---

## The two models

Both are downloaded once, from settings, and then never contacted again.

| | Parakeet TDT 0.6B v2 | Qwen 3 0.6B |
|---|---|---|
| **Job** | Speech → text | Text → better text |
| **Runtime** | sherpa-onnx (ONNX, int8) | LiteRT-LM (mixed int4) |
| **Download** | ~482 MB, required | ~498 MB, optional |
| **Process** | the keyboard | a separate `:llm` process |
| **Licence** | CC-BY-4.0 | Apache-2.0 |

Parakeet transcribes each utterance once, when you stop speaking — one accurate
pass instead of a stream of guesses that rewrite themselves. Qwen runs behind an
AIDL interface in its own process, so a native out-of-memory takes the refiner
down and leaves your keyboard standing.

The refiner is **optional**. Without it you still get dictation and the whole
deterministic rules tier; the keyboard tells you plainly when the smart pass
could not run rather than pretending it did.

---

## Privacy is the architecture

Not a policy — a process split the build enforces.

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│   keyboard   │   │     :llm     │   │     :ui      │
│  IME + ASR   │   │  Qwen 3 0.6B │   │   settings   │
│              │   │              │   │  + downloads │
│  no network  │   │  no network  │   │   INTERNET   │
└──────────────┘   └──────────────┘   └──────────────┘
```

`ManifestProcessSplitTest` fails the build if a component holding `INTERNET`
ever moves out of `:ui`. Audio is never written to disk, never sent anywhere,
and the utterance buffer is cleared when the session ends.

---

## Requirements

| | |
|---|---|
| **Android 7.0** (API 24) or newer | The floor the models impose: LiteRT-LM, which runs the refiner, declares `minSdkVersion 24`. Below it there is no on-device AI to offer, so the keyboard does not install and pretend otherwise. |
| **A 64-bit ARM phone** (`arm64-v8a`) | Every phone since about 2016. The refiner checks for a 64-bit ABI at runtime and stays off without one; the published APK is arm64 only. |
| **A microphone** | Dictation adds `RECORD_AUDIO` to the permissions HeliBoard already asks for. |
| **~1 GB free**, ~2 GB with the refiner | 482 MB for the speech pack and 498 MB for the LLM, plus room to unpack. |
| **Internet once** | For the model download, from the settings process only. The keyboard process has no network component at all. |

Nothing else: no account, no API key, no paid service, and no network after the models
land.

**To build:** JDK 21, Android SDK 36. The Gradle 9.6 wrapper and a 4 GB build heap are
configured in the repo.

## Run it

```bash
git clone https://github.com/nvkudva/WaveKey.git
cd WaveKey
./gradlew :app:assembleDebug
# ABI splits produce one APK per architecture; releases ship arm64-v8a
adb install app/build/outputs/apk/debug/WaveKey_1.0-beta-debug-arm64-v8a.apk
```

"WaveKey" then appears in Android's keyboard list. Enable it, switch to it, and open
its settings to download the speech model — the microphone key does nothing until the
required pack is installed.

### Release signing

There is no runtime configuration. Two environment variables affect release builds only,
and only when a keystore exists at `~/.wavekey/release.jks` (the old
`~/.supervoiceboard/release.jks` is still read if that one is absent). Without either
file the release build still succeeds and comes out unsigned.

| Variable | Required | What it is |
|---|---|---|
| `WAVEKEY_STORE_PASSWORD` | No | Keystore password for release signing. `SVB_STORE_PASSWORD` still works |
| `WAVEKEY_KEY_PASSWORD` | No | Key password for the signing alias. `SVB_KEY_PASSWORD` still works |
| `WAVEKEY_KEY_ALIAS` | No | Signing alias; defaults to `supervoiceboard`, which is what existing keystores hold |

---

## How it is built

WaveKey is a fork of [HeliBoard](https://github.com/Helium314/HeliBoard) 4.1 (base commit
`9f5bb63`). Typing — layouts, glide typing, dictionaries, themes — is HeliBoard's,
unchanged. This repo adds the voice and AI layer.

| Module | What lives there |
|---|---|
| `core/` | Pure Kotlin JVM, no Android. The decisions: `DictationStateMachine`, `TranscriptCleaner`, `TextFixer`, `PackInstaller`. About two thirds of the module by line count is tests. |
| `voice/` | The Android half. `VoiceSessionController` runs the state machine's effects against `AudioCapture` and the Parakeet recognizer. It references no HeliBoard class, so it could be mounted in another IME. |
| `llm/` | Qwen 3 under LiteRT-LM in the `:llm` process, behind `ILlmRefiner.aidl`. |
| `app/` | The binding. `VoiceController` is the only class that writes to the `InputConnection`; `AiFixKey` mounts the AI fix key. |

Issues about the voice layer belong on this repo's tracker; issues about typing belong
upstream.

## Status

**1.0-beta.** Working today: dictation, on-device cleanup, the AI fix key, sentence
rescoring, and model download and install from settings.

Verified on the current tree:

| | |
|---|---|
| Unit tests | `core` 932 · `voice` 72 · `app` 214 — all passing |
| Android lint | 0 errors |
| Release build | assembles and signs |
| Model integrity | both packs pinned to an immutable revision and a SHA-256 the installer checks |

CI runs the `core`, `voice` and app unit tests, a debug assemble, Android lint, and an
emulator UI QA suite.

### Known gaps

Honest ones, from a code review of `voice/`, `llm/` and the voice code in `app/`:

- **Late refinement can delete characters typed after a commit.** `replaceUtterance`
  removes `previous.length` characters before the cursor without checking that they are
  still the text it committed.
- **`llm/` has no unit tests**, and `voice/` — which holds most of the fork's
  concurrency — is only part-way there: the root rules, the level meter, the silence
  timeout and idle release are covered; `VoiceSessionController` is not.
- **A timed-out `bindService` leaves the binding in place**, pinning the `:llm` process
  and its model until something else tears it down.
- **No coroutine exception handler on the IME-process scopes**, so an uncaught throw in
  a finalize, refine or fix coroutine reaches the default handler and takes the keyboard
  with it.

Nothing about speed, accuracy or battery has been measured, and dictation is English
only.

## License

GPL-3.0-only — see [LICENSE](LICENSE). The AOSP Keyboard base is Apache-2.0
([LICENSE-Apache-2.0](LICENSE-Apache-2.0)), the launcher icon is CC-BY-SA-4.0
([LICENSE-CC-BY-SA-4.0](LICENSE-CC-BY-SA-4.0)), and the default icon set is MIT
([LICENSE-MIT-fluent-icons](LICENSE-MIT-fluent-icons)).
