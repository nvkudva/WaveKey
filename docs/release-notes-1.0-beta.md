# WaveKey 1.0-beta

The first WaveKey release. An Android keyboard that transcribes speech, cleans it
up and rewrites it, with both models running on the phone — no account, no API
key, no server, and no network permission in the keyboard process at all.

## Read this first if you installed an earlier build

**The app id changed**, from `com.supervoiceboard.app` to `com.wavekey.keyboard`.
Android treats that as a different app, so this installs *alongside* anything you
had rather than upgrading it. After installing:

1. Enable and switch to the new WaveKey in Android's keyboard settings.
2. Uninstall the old one — it keeps its own copy of the models, about a gigabyte.
3. Download the speech model again from WaveKey's settings. Settings and models do
   not carry across; nothing else is lost.

This is the last time that happens. The id is what it will stay.

## What is in it

- **Dictation** with Parakeet TDT 0.6B, on the phone. The mic opens the moment you
  press it — audio is buffered while the model loads, so the first words of a cold
  start are not lost — and stays live while text lands, so a pause ends the
  sentence rather than the session.
- **AI fix**, one key: a deterministic rules pass for spacing, casing, duplicated
  words and terminal punctuation, then Qwen 3 0.6B over the phrasing. Press again
  to stop the run, once more to put your original text back.
- **Continuous fixing** while you type, and sentence rescoring that shows you the
  word it swapped instead of slipping it past you.
- **A clipboard that reads like a list** — typed clips with a glyph, a size or a
  host, and how long each has left.
- **Everything else is HeliBoard 4.1**: layouts, glide typing, dictionaries,
  themes.

## Requirements

Android 7.0 (API 24) or newer, a 64-bit ARM phone, a microphone, and about 1 GB
free for the speech model — 2 GB if you add the optional text-correction model.
Internet once, for that download, from the settings process only.

## Known gaps

- Late refinement can delete characters typed immediately after a commit.
- `llm/` has no unit tests, and `VoiceSessionController` is not covered yet.
- A timed-out service bind leaves its binding in place, pinning the model process.
- No coroutine exception handler on the IME-process scopes.
- Speed, accuracy and battery are unmeasured. Dictation is English only.
