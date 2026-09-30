# Private mode — UX specification

Design pass for the "go private" feature: WaveKey ships defaulting to Google's speech
recognizer and Gemini Nano, and offers one control that moves everything on-device in a
single decision. Written against the Compose settings in
`app/src/main/java/helium314/keyboard/settings/`, the naming in
`app/src/main/res/values/strings_voice.xml`, and the reorganisation recorded in
`docs/settings-ia.md`.

Every string below is final copy, not a placeholder. Resource names use the existing
`wk_` prefix; composable names follow `VoiceModelsSection` / `EngineChoice`.

## The principle behind the copy

The user is choosing between two honest trades:

- **Google**: works immediately, costs nothing, needs Google, and your voice leaves the phone.
- **Private**: nothing leaves the phone, and costs about a gigabyte of storage and a download.

Both sentences get said, at the same size, in the same voice. No option is decorated as the
safe one or the smart one. We do not write "protect your privacy", "secure", "military-grade",
"just", "simply", or "don't worry". We do not write "your data will be harvested" either. The
user is told what happens and what it costs, and then left to choose.

Sizes are never hardcoded in copy. Every size is `ByteSize.format(...)` of a real sum from
`ModelCatalog`, so a catalogue change cannot make a string lie. Where this document prints
980 MB it means "the formatted sum of the speech pack and the refiner pack".

---

## 1. Voice settings layout

### Where it sits

Top of the Voice screen, as the first thing in the first `PreferenceGroup`, above
everything. It replaces two things that are there today:

| Today, top of `VoiceScreen` | After |
| --- | --- |
| `VoiceModelsSection(only = FINAL_ASR)` — one pack card with its own 482 MB button | gone from this screen |
| `EngineChoice()` — Google / on-device radio pair | becomes the mode pair inside the new block |
| `VoiceModelsSection(only = REFINER_LLM)` under the *AI Correction* category | gone from this screen |

Per-pack rows do not disappear from the product — they stay on `VoiceModelsScreen`, which is
where import, remove and one-pack retry belong. The Voice screen reaches it through the new
block's overflow item **Manage models one by one**. So the Voice screen holds the decision and
the models screen holds the maintenance, instead of both screens holding half of each.

New composable: `PrivateModeSection()` in `VoiceScreen.kt`, with `EngineOption` reused
unchanged for the two rows. The *AI Correction* category keeps its switches
(`VoiceKeys.LLM_REFINE` and the journal rows) and loses its pack card.

### Shape

```
┌ How dictation works ─────────────────────────────┐   PreferenceCategory
│ ( ) Google                                   ⋮   │   EngineOption (existing)
│     <one line, branches on recognizer>           │
│ ( ) Private — everything on this phone           │   EngineOption (existing)
│     <one line, branches on install state>        │
│ ─────────────────────────────────────────────    │   PreferenceGroupDivider
│ <disclosure line — permanent, not dismissible>   │
│ [ Go private · 980 MB            full width ]    │   FilledTonalButton
└──────────────────────────────────────────────────┘   PreferenceGroup
```

One container, one full-width primary action, overflow in the trailing slot of the first row.
This is the `PackRow` layout the user already knows from `VoiceModelsScreen`: a decision worth
several hundred megabytes gets its own line, never a chevron slot, and the primary slot never
turns into a destructive verb under a thumb that learned it meant "get".

### The radio does not lie

Selecting **Private** while the models are missing does not switch the engine. It starts the
download (or, on a metered link, opens the existing metered confirmation first) and leaves the
radio on **Google** until the install finishes. The row's own description says what is
missing. This is deliberate: today's code disables the on-device row when the model is absent,
which is correct about state but dead-ends the user. Enabling the row and committing the mode
only at install keeps both true — the user can act, and the selected radio always names the
engine that would actually run right now.

---

## 2. The copy, state by state

### State A — default, nothing downloaded (fresh install)

```xml
<string name="wk_cat_processing">How dictation works</string>

<string name="wk_mode_google">Google</string>
<string name="wk_mode_google_network">Your voice is sent to Google to be turned into text. Works now, nothing to download.</string>
<string name="wk_mode_google_local">Handled by your phone\'s own recognizer. Nothing is sent to Google. Works now, nothing to download.</string>

<string name="wk_mode_private">Private — everything on this phone</string>
<string name="wk_mode_private_missing">Nothing is sent anywhere. Needs a %1$s download and about that much storage.</string>

<string name="wk_private_go">Go private · %1$s</string>
```

`wk_mode_google_network` and `wk_mode_google_local` branch on
`GoogleVoiceSession.onDeviceAvailable(ctx)`, exactly as `wk_engine_google_local` /
`wk_engine_google_network` do today. A phone with an offline recognizer pack is not sending
audio anywhere and must not be told that it is.

Card colour in this state is `surfaceContainerHighest`, **not** `primaryContainer`. Today the
no-models card is tinted `primaryContainer` because with no model the keyboard cannot dictate
at all. Once Google is the default, a fresh install is not broken and the card must not
perform urgency for a download the user does not need.

### State B — private mode active

```xml
<string name="wk_mode_private_on">Nothing leaves this phone. Speech and text cleanup both run here.</string>
<string name="wk_mode_google_off">Not in use.</string>
<string name="wk_private_installed">Installed · %1$s of storage</string>
```

No primary button. The only things left to do with an installed bundle are in the overflow,
which is where `PackRow` already puts them.

### State C — private mode active, refiner absent by choice or by failure

```xml
<string name="wk_mode_private_speech_only">Speech runs on this phone. Text cleanup uses the built-in rules, which need no download.</string>
<string name="wk_private_add_refiner">Add AI text cleanup · %1$s</string>
```

This is a real, supported end state, not a broken one. The deterministic cleanup
(`RAW_TRANSCRIPT` off, punctuation, casing, fillers) runs with no model at all, and the copy
says so plainly rather than nagging.

### Overflow

```xml
<string name="wk_private_manage">Manage models one by one</string>
<string name="wk_private_remove">Remove the private mode models</string>
<string name="wk_private_remove_message">Frees %1$s. Dictation goes back to Google, and your voice will be sent there again. You can download the models again later.</string>
```

The removal dialog names the privacy consequence, because removing the models is also the act
of turning the cloud back on, and the user is entitled to be told that in the dialog rather
than discovering it at the next dictation.

---

## 3. The download, as one block

One decision, one size, one bar, one cancel. The two packs are an implementation detail of a
thing called "private mode" and the UI never asks the user to think about them separately
while the download is in flight.

### Size

`ByteSize.format(ModelCatalog.packs.filter { it.kind == FINAL_ASR || it.kind == REFINER_LLM }
.sumOf { it.totalBytes })`. If part of the bundle is already installed, the button's size is
the sum of what is still **missing**, so re-entering after a cancel offers a smaller number
and the number is always the truth about what this press will cost.

### Progress

One `LinearProgressIndicator`. Its fraction is bytes-weighted across the whole bundle
(`downloadedBytes / missingBytes`), never "pack 1 of 2" — a pack-counted bar jumps and stalls
and teaches the user that the bar is lying.

```xml
<string name="wk_private_downloading">Downloading · %1$d%% of %2$s</string>
<string name="wk_private_downloading_stage">Speech model, then text cleanup.</string>
<string name="wk_private_queued">Waiting for Wi-Fi.</string>
<string name="wk_private_verifying">Checking the download…</string>
<string name="wk_private_installing">Installing…</string>
```

The stage line is a static sentence under the bar. It tells the user the order without
turning into a second, competing progress readout.

Metered links reuse `DownloadPolicy.decide` and the existing metered dialog unchanged — the
only change is that the bytes named in it are the bundle's, not one pack's.

### Cancel

```xml
<string name="wk_private_cancel">Cancel download</string>
<string name="wk_private_cancel_title">Stop downloading?</string>
<string name="wk_private_cancel_message">%1$s is already downloaded and will be kept. The rest is discarded. Dictation stays with Google.</string>
<string name="wk_private_cancel_confirm">Stop</string>
<string name="wk_private_cancel_keep">Keep downloading</string>
```

Cancel keeps every pack that finished installing, discards the partial bytes of the one in
flight, and does **not** change the mode: the radio was never moved off Google, so there is
nothing to roll back. When the kept part is zero the message is
`wk_private_cancel_message_none`:

```xml
<string name="wk_private_cancel_message_none">Nothing is installed yet, so nothing is kept. Dictation stays with Google.</string>
```

After a cancel the card returns to State A with the button size reduced to what is missing.

### Partial failure

The bundle succeeding halfway is the common case — the speech pack lands, the refiner's
connection drops. It is reported as a specific outcome with a specific next step, not as a
failed download.

```xml
<string name="wk_private_partial_speech">Private mode is on. The text cleanup model did not finish downloading, so cleanup is using the built-in rules for now.</string>
<string name="wk_private_partial_retry">Finish the download · %1$s</string>
<string name="wk_private_partial_dismiss">Leave it</string>
<string name="wk_private_failed_none">Nothing was installed. Dictation is still using Google.</string>
<string name="wk_private_failed_reason_network">The connection dropped.</string>
<string name="wk_private_failed_reason_storage">This phone needs %1$s free and does not have it.</string>
<string name="wk_private_failed_reason_checksum">The downloaded file did not match what we expected, so it was discarded.</string>
<string name="wk_private_retry">Try again · %1$s</string>
```

The reason maps from the existing `InstallError` enum, one sentence, appended after the
outcome. **Leave it** is a first-class answer: speech-only private mode is State C, which is
a place the product is happy to leave someone.

If the speech pack fails and the refiner succeeds, the mode does not switch — dictation still
needs the speech model — and the copy is `wk_private_failed_speech`:

```xml
<string name="wk_private_failed_speech">The speech model did not download, so dictation is still using Google. The text cleanup model installed and is in use.</string>
```

---

## 4. The honest disclosure

Routing audio to Google is a change from what this fork has said about itself. The disclosure
has to be read, not dismissed, and it has to be said once rather than nagged.

**Where it is said:**

1. **In the first-run wizard, on the last screen** (section 6, screen 4), as body copy the
   user walks through — not a dialog with an OK button.
2. **Permanently on the Voice screen**, as the line between the mode rows and the primary
   action. Not a banner, not dismissible, no close affordance. It is a label on a control, so
   it stays as long as the control does.
3. **Nowhere else.** No toast on each dictation, no badge on the mic key, no repeat dialog.

```xml
<string name="wk_disclosure_google">While Google is selected, the audio of what you say is sent to Google\'s speech service to be turned into text, the same service your phone\'s other voice input uses. What you type is not sent. Text cleanup runs on this phone either way.</string>
<string name="wk_disclosure_google_local">Your phone has an offline recognizer pack, so this audio is handled on the device and is not sent to Google. If that pack is removed, audio will be sent instead.</string>
<string name="wk_disclosure_private">Nothing you say or type is sent anywhere. WaveKey only uses the network to download these models, and only when you ask it to.</string>
```

Three sentences, one paragraph, `bodySmall` on `onSurfaceVariant`. It names the payload
(audio), the recipient (Google), the exclusion (typing), and the part that is unaffected
(cleanup). The `_local` variant exists because on a phone with an offline pack the plain
sentence would be false, and a privacy disclosure that overclaims is as bad as one that
underclaims.

`PrivacyBreakingScreen` keeps `PREF_GOOGLE_VOICE` and its summary, but its summary changes
from describing a switch the user turned on to describing the shipped default:

```xml
<string name="privacy_breaking_google_voice_summary">On by default. Sends dictation audio to Google instead of using the models on this phone. Turn it off in Voice settings by going private.</string>
```

---

## 5. Degraded devices

These are states, not errors. Each one removes an option, says which and why in one sentence,
and leaves a working path.

### No Gemini Nano (AICore absent, or device unsupported)

Probe via the `tools/nanocheck` path. Nano's absence affects text cleanup only; dictation is
untouched.

```xml
<string name="wk_nano_absent">This phone does not have Gemini Nano, so AI text cleanup needs WaveKey\'s own model. Punctuation, casing and filler removal work without it.</string>
```

The *AI Correction* category shows this line in place of the Nano option, and the local
refiner download stays offered at its own size. No dialog, no asterisk on the mode rows —
Nano is not what the mode chooser is about.

### No system speech recognizer

`GoogleVoiceSession.isAvailable()` is false, so the default is not available on this device and
the mode chooser has nothing to choose between.

```xml
<string name="wk_mode_google_unavailable">This phone has no speech recognizer of its own, so this is not available here.</string>
<string name="wk_private_required">Dictation on this phone needs WaveKey\'s own models.</string>
<string name="wk_private_required_go">Download · %1$s</string>
```

The Google row renders `enabled = false` at the existing `alpha(0.5f)` with that description —
present and explained, rather than silently absent — and the block's disclosure line becomes
`wk_private_required`. The card may use `primaryContainer` in this state, since here dictation
genuinely cannot run until something is downloaded. This is the one state where the old urgent
tint is still correct.

### 32-bit ARM (`refinerAbiSupported` false)

The refiner cannot run at all, so the bundle is the speech pack alone: the combined size drops
to 482 MB and State C is the only private end state. The refiner is not mentioned, not greyed,
not counted in the size — the existing `listOfNotNull` pattern already drops switches that
could never do anything, and the same rule applies to the download.

---

## 6. First-run wizard

`WelcomeWizard.kt` keeps four steps and its `StepProgress` bars. Steps 1 and 2 are unchanged.

**Screen 3 — microphone.** The current instruction says "the speech never leaves your
device", which the new default makes false. Replaced:

```xml
<string name="setup_mic_instruction">%s types what you say. Allow the microphone so the voice key is ready when you are.</string>
```

The privacy claim is removed rather than reversed: the mic screen is about a permission, and
the next screen is where the handling is explained in full. Splitting the disclosure across
two screens would mean saying it twice and weakly.

**Screen 4 — finish, and the choice.** The step currently titled *Download the AI model now*
stops being an upsell for a download and becomes the disclosure plus a genuine fork. The
wizard no longer starts a download on the way out unless the user picks it here.

```xml
<string name="setup_privacy_title">How your voice is handled</string>
<string name="setup_privacy_body">Out of the box, the audio of what you say is sent to Google to be turned into text — the same service your phone\'s other voice input uses. It is fast and there is nothing to download.\n\nIf you would rather nothing left the phone, WaveKey can do all of it here instead. That needs a %1$s download and about that much storage, and you can set it up now or any time from Voice settings.</string>
<string name="setup_privacy_keep">Start typing</string>
<string name="setup_privacy_go">Go private · %1$s</string>
```

Two actions, both real. **Start typing** is the `Button` (it finishes setup, which is what the
screen is for); **Go private** is the `SecondaryAction` below it and enqueues the bundle, then
lands on the Voice screen so the combined progress is visible where the setting lives. Neither
is styled as the recommended one, and there is no third "learn more" that lets the user finish
setup without having read anything.

On a device with no system recognizer, screen 4 collapses to one action: the body is replaced
by `wk_private_required` and the only button is `wk_private_required_go`, with **Not now**
still available.

---

## 7. Accessibility and dark mode, where they changed a decision

- **One progress bar, one announcement.** The combined bar carries a single
  `ProgressBarRangeInfo` and a `contentDescription` of `wk_private_downloading`, following the
  pattern already in `PackRow`. Per-pack bars are not rendered, so TalkBack cannot be handed
  two progress values for one download and no live region announces the pack handover — the
  static stage line covers it instead.
- **The disclosure is not a banner.** A dismissible banner is reachable by swipe-to-dismiss
  and is skipped by users who navigate by heading. As a label between the radio group and its
  action, it is inside the traversal order of the control it describes and cannot be lost.
- **The mode rows stay a radio group.** `selectableGroup` + `Role.RadioButton` gives "1 of 2"
  and mutual exclusivity for free. A single "Private mode" switch would read as a toggle whose
  off state has no name, and the off state here is a specific thing — Google — that the user
  needs to hear named.
- **The disabled Google row keeps its text.** `alpha(0.5f)` alone drops the row below contrast
  minimums, so the unavailability reason is also in the row's description, which TalkBack
  reads regardless of alpha.
- **Colour is never the state.** Partial failure colours only the reason line
  `colorScheme.error` and leaves the card at its normal container colour. On the WaveKey dark
  ground (#0A0D22) an error-tinted card reads as a crash; and an error colour carrying the
  whole meaning is invisible to a user who cannot see it.
- **Both container tints were checked in both schemes.** `surfaceContainerHighest` for the
  neutral default state and `primaryContainer` only for "dictation cannot run" — the tint
  difference has to survive dark mode because it is the only non-textual cue distinguishing
  "you have a choice" from "you must act", and it is backed by the text in both cases.

---

## Open questions for the PM

- Does the shipped default flip to Google for **existing** installs that already have the
  speech model, or only for fresh ones? Recommendation: never move a user who already paid the
  gigabyte — an install with the speech model present starts in private mode and the
  disclosure line shows the `wk_disclosure_private` variant.
- Is Gemini Nano the default text cleanup where it exists, or is the deterministic pass the
  default with Nano opt-in? The copy above assumes Nano-when-present, since it is on-device and
  costs nothing.
- Does "private mode" need a visible marker on the keyboard itself, or is the settings state
  enough? The mic key currently says nothing about where audio goes.
