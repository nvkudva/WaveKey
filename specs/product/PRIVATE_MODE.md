# Private mode — requirements

Status: proposed. Owner: product. Branch: `feat/local-mode`.

This spec defines what WaveKey does out of the box, and what the single
"Go private" control in voice settings does. It is a requirements document: it
says what must be true, not how to build it. Visual design, copy and layout are
specified separately in `specs/product/PRIVATE_MODE_UX.md`.

## 1. The problem

Today a fresh install cannot refine text at all and can only dictate by
borrowing the platform's offline recognizer, because both of our own models are
large downloads: the Parakeet recognizer is 482 MB and the Qwen refiner is
498 MB. The recognizer is still marked `required` in `ModelCatalog`, so setup
presents a ~482 MB download as the price of using the headline feature. Users who
decline get a keyboard whose mic key is a lottery, and users who accept wait.

Meanwhile two capabilities already exist on many devices and cost nothing to
install: the platform `SpeechRecognizer` (wrapped by `GoogleVoiceSession`, with
an on-device path on API 31+) and Gemini Nano through AICore for text
refinement. The product decision is to make those the default, and to make going
fully on-device a single, honest, reversible choice.

## 2. Vocabulary

Words the product uses, and the words it must not use.

| Concept | User-facing term | Internal term |
| --- | --- | --- |
| Dictation by the platform recognizer | Device voice typing (Google) | system recognizer, `GoogleVoiceSession` |
| Refinement by Gemini Nano via AICore | Device AI (Gemini Nano) | Nano refiner backend |
| WaveKey's own downloaded models | WaveKey's own models | local packs, `ModelCatalog.packs` |
| Both local packs installed and selected | Private mode is on | local mode complete |
| The default, mixed state | Private mode is off | cloud-capable default |

"Private mode" is the user-facing name of the feature. The task brief calls it
"go private/local mode"; the shipped label is **Private mode**, with the action
labelled **Go private**. We do not ship the phrase "local mode" in the UI, and we
never call the default state "insecure", "unsafe" or "cloud mode" — the default
may still be fully on-device on many devices, and mislabelling it would be a
lie.

## 3. Default experience on a fresh install

### 3.1 What runs

1. Dictation is handled by the platform recognizer. Where
   `SpeechRecognizer.createOnDeviceSpeechRecognizer` is available (API 31+ with
   an installed system speech pack), the on-device path is used and no audio
   leaves the phone. Where it is not, the network recognizer is used, and audio
   leaves the phone.
2. Text refinement (the AI fix key and, when enabled, post-dictation
   refinement) is handled by Gemini Nano through AICore where the device
   supports it.
3. Deterministic cleanup (`:core` text pipeline: transcript cleanup, spoken
   formats, commit planning, content guard) always runs locally and is
   unaffected by any of this. It is not a model and is never downloaded.
4. Nothing is downloaded by WaveKey during setup. Dictation must work on the
   first mic press on a supported device with zero bytes fetched.

### 3.2 Default preference values

- `PrivacyBreakingSettings.DEFAULT_GOOGLE_VOICE` changes from `false` to `true`.
- The change applies to fresh installs only. An existing install that has never
  touched the key must keep behaving as it did: a one-time migration writes the
  old default (`false`) explicitly for any install that already has WaveKey
  models installed, or that has recorded any voice session. An upgrade must
  never silently start routing a current user's audio through the network
  recognizer.
- `ModelCatalog` no longer expresses "you need this to use WaveKey". The
  recognizer pack stays `required = true` in the sense `ModelReadiness` defines
  it — required *for on-device dictation* — but no first-run surface may present
  `requiredPacks` as a precondition for using the keyboard.

### 3.3 The consent obligation

Routing audio to the network recognizer is a privacy change from today's
behaviour, so it is disclosed before it happens, not after.

1. Setup shows one screen naming what handles dictation on this device, in plain
   terms, with the two choices: continue with device voice typing, or go
   private. See §7.
2. If the user skips setup entirely and the device has **only** the network
   recognizer, the first mic press shows a one-time sheet that states audio will
   be sent to the device's speech service, with **Continue** and **Go private**.
   No audio is captured until it is dismissed with Continue.
3. If the device has the on-device system recognizer, no such sheet appears —
   there is nothing to disclose that setup did not already say.
4. The consent is recorded per install, not per session, and is revoked when the
   user turns private mode on.

### 3.4 Process and permission constraints (non-negotiable)

- The IME process must never gain `INTERNET`. The existing audit test must keep
  passing. Both defaults are compatible with this: `SpeechRecognizer` and AICore
  do their networking in their own system processes, and WaveKey only binds to
  them. No new permission is introduced by the default experience.
- All WaveKey downloads continue to run in the `:ui` process via
  `ModelDownloadService` and WorkManager.

## 4. Degraded devices

The rule: state what is actually available, offer the one action that helps, and
never present a control that cannot work.

| Device | Dictation | Refinement | What the user is told |
| --- | --- | --- | --- |
| On-device system recognizer + Nano | System recognizer, offline | Nano | Everything already stays on the device; private mode replaces Google's models with WaveKey's own. |
| Network recognizer only + Nano | Network recognizer | Nano | Dictation is sent to the device's speech service. Private mode stops that. |
| On-device recognizer, no Nano | System recognizer, offline | Deterministic cleanup only; AI refinement rows read as unavailable | This device has no built-in text AI. WaveKey's own text model adds it. |
| No recognizer at all, no Nano | Nothing until a download | None | The mic key cannot work yet. Private mode is the only path, and is presented as the primary action rather than an option. |
| No recognizer, Nano present | Nothing until a download | Nano | As above for dictation; refinement already works. |
| 32-bit ABI (`refinerAbiSupported` false) | Unchanged | Nano only, never WaveKey's refiner | Private mode covers dictation only on this device, and its size and copy say so. |

Requirements:

1. Availability is probed, never assumed from an API level alone: the recognizer
   through `GoogleVoiceSession`, Nano through an AICore feature-status query.
2. A device where neither WaveKey pack can be installed (ABI unsupported *and*
   no storage) must not show a broken Go private control; it shows a single
   disabled row stating the reason.
3. Where Nano reports that its feature needs a system-side download, WaveKey may
   request it and shows "Preparing device AI". Those bytes belong to AICore and
   must **not** be counted in private mode's size or progress. Mixing the two
   numbers is the exact confusion this feature exists to remove.
4. The existing automatic fallback in `VoiceController.fallbackWouldRun` stays,
   with its current guarantee intact: an automatic fallback may only use the
   platform's *on-device* recognizer, never the network one. Automatic behaviour
   never sends audio off the device.
5. Any state where nothing can dictate must produce a spoken-language error on
   the voice strip, not silence.

## 5. "Go private" — one control, one decision

### 5.1 Placement and shape

One control at the top of the Voice settings screen, above everything else it
governs. It is a single row that expands into the state detail below it; it is
not a switch, because the on-transition costs ~980 MB and a switch promises
instant. It replaces the current `EngineChoice` radio pair as the primary
engine decision (see §6 for what happens to that control).

### 5.2 The combined figures

- **Download size** is the sum of the not-yet-installed packs in the bundle,
  computed from `ModelPack.totalBytes` and shown as one number. With neither
  pack installed this is 980 MB (482 MB + 498 MB).
- **Storage needed** is the sum of `installFootprintBytes` — about 1.7 GB peak,
  because the recognizer archive coexists with its extracted contents. The
  pre-flight storage check uses this figure, and the user-facing copy quotes the
  download size with the peak figure available in the detail line, never the
  other way round.
- On a device where the refiner cannot run (`refinerAbiSupported` false), the
  bundle is the recognizer alone, and every figure and every piece of copy
  reflects that automatically. The bundle is derived, never hardcoded.
- The bundle is language-aware: it contains the FINAL_ASR packs that
  `ModelCatalog.asrPacksFor` returns for the active subtype's language, plus the
  REFINER_LLM pack. A language with no on-device recognizer cannot offer private
  mode for dictation, and the control says so rather than downloading an English
  model for French speech.

### 5.3 States

Exactly one of these is true at any time, and the control renders exactly one.
Per-pack `PackState` values are inputs to this; they are never shown as the
headline.

1. **Unavailable.** No pack in the bundle can be installed on this device or in
   this language. The row is present, disabled, and names the reason (language,
   ABI, or no storage). No action.
2. **Off (not started).** No bundle pack is installed. Headline: what handles
   dictation and refinement today. Action: **Go private — 980 MB**.
3. **Confirming.** The user tapped Go private. One dialog states: the download
   size, the peak storage figure, that it runs on Wi-Fi unless they allow mobile
   data, and what changes when it finishes. If `DownloadPolicy.decide` returns
   `ConfirmMetered`, the mobile-data question is part of this same dialog — not a
   second one after it. Actions: **Download**, **Cancel**. Cancelling here
   returns to Off and records nothing.
4. **Queued.** Work is enqueued but not running, because the device is offline
   or waiting for an unmetered link (`Enqueue(startsImmediately = false)`). The
   control says which, and shows no progress bar. A stalled bar reads as broken.
   Actions: **Use mobile data** (where applicable), **Cancel**.
5. **Downloading.** One determinate progress bar for the whole bundle: bytes
   done over bytes total, summed across all bundle packs, with installed packs
   counted as complete. One percentage, one byte counter, no per-pack bars in
   the headline. A single secondary line may name the file being fetched. The
   existing foreground notification shows the same combined figure and the same
   percentage as the screen. Actions: **Cancel**.
6. **Installing.** Verifying and extracting (`PackState.Verifying` and the
   extraction that follows). Progress becomes indeterminate with the label
   naming the step. Cancel is still offered; it must not corrupt an installed
   pack.
7. **Failed.** Any bundle pack reached `PackState.Failed` with an error other
   than `CANCELLED`. The control shows one cause, in the user's terms, mapped
   from `InstallError`: no connection, out of space (with the figure needed), the
   file did not match what we expected, or a storage error. Packs that did
   finish stay finished. Actions: **Retry** (resumes the bundle from what is
   present), **Cancel** (drops to Off or Partly on). Dictation continues on the
   default engine throughout; a failed download must never leave the keyboard
   unable to type or dictate.
8. **Partly on.** Some but not all bundle packs are installed, and the user is
   not currently downloading. This is a first-class state, not an error: it is
   where a cancelled or failed bundle lands, and where a user who only wants
   local dictation can legitimately stop. The control names exactly what is
   local and what is not, and offers **Finish private mode — 498 MB** (the
   remaining figure). If the installed pack is the recognizer, dictation is
   switched to it (see §5.4); refinement stays on Nano.
9. **On (complete).** Every bundle pack is installed and selected. Headline:
   nothing leaves this device. Actions: **Turn off** (see §5.5), and, in the
   detail, per-pack management.
10. **Reverting.** Transient state while the user turns it off: the engine
    preference flips back, and the user is asked once what to do with the
    downloaded models (§5.5). Resolves to Off or Partly on within one frame; it
    is never a spinner.

### 5.4 Switching on

- The engine switch is not gradual. The moment the recognizer pack reaches
  `Installed`, dictation switches to it: the code writes
  `PREF_GOOGLE_VOICE = false`. A session already running keeps the backend it
  started with, matching the existing `toggleAsrEngine` rule.
- The moment the refiner pack reaches `Installed`, refinement prefers it over
  Nano.
- Private mode reports **On** only when both are true for the current bundle.
  Until then it is Partly on and says which half is which.
- Turning private mode on revokes any network-recognizer consent recorded under
  §3.3. Turning it off later must ask again.

### 5.5 Reversibility

Private mode is fully reversible, and reverting is free.

1. **Turn off** flips the engine preference back to the platform recognizer and
   refinement back to Nano, immediately, with no download and no deletion.
2. Downloaded models are **kept by default**. Deleting a gigabyte the user paid
   for to re-download it later is the worse default; disk is recoverable, the
   download is not.
3. The turn-off confirmation offers **Keep models** (default, focused) and
   **Delete and free 980 MB** as an explicit secondary choice. The delete path
   reuses the existing per-pack removal confirmation semantics.
4. Deletion is never automatic, never implicit in turning off, and never
   triggered by low storage on our behalf.
5. Turning private mode back on with both packs still present is instant: no
   download, no dialog, no progress. This must be visibly instant, because it is
   the proof that the choice is cheap.

## 6. The settings surface afterwards

1. The Voice screen's first group is private mode: the control from §5, and
   directly beneath it a plain statement of what runs dictation and what runs
   refinement right now. That statement is the successor to today's engine
   descriptions and must name the actual backend, including the automatic
   fallback case.
2. The two engine radio buttons (`EngineChoice`) are removed from the Voice
   screen as a top-level choice. The engine is a consequence of private mode and
   of what is installed, not a separate decision the user has to reconcile with
   it. Two controls that can contradict each other is the bug we are fixing.
3. The manual override survives, subordinate: inside the private mode detail,
   when both engines are usable, a single "Use Google's recognizer instead" row
   lets the user pick the platform recognizer while WaveKey's models stay
   installed. It is the only place `PREF_GOOGLE_VOICE` is directly editable from
   the Voice screen.
4. The two per-pack rows (`VoiceModelsSection`) no longer appear inline on the
   Voice screen. They keep existing, unchanged in function — download, cancel,
   remove, import, license note, size, state — on the **Voice models** screen,
   which becomes the detail view reached from the private mode control. Per-pack
   control is for people who want it; it is not the way the feature is
   understood.
5. The Voice models screen gains a header stating which private mode state the
   packs add up to, so the two surfaces can never disagree.
6. The toolbar ASR-engine key keeps working and keeps writing the same
   preference. Its toast must name what will actually run, as it already does.
7. `PrivacyBreakingScreen` keeps the Google voice switch as the low-level
   preference it is, and gains a line pointing at private mode. It is not the
   place the feature is discovered.

## 7. Onboarding

1. The model-download offer on the wizard's last step is removed. Setup never
   asks for a several-hundred-megabyte download again.
2. The mic permission step is unchanged.
3. The last step gains one short statement of what handles dictation on this
   device, phrased from the probe result, and two actions: **Done** (primary)
   and **Go private** (secondary), which opens Voice settings with the private
   mode control focused. Tapping it never starts a download from the wizard; the
   decision is made where the size and the states live.
4. On a device with no recognizer at all, that statement says the mic key cannot
   work yet and **Go private** becomes the primary action.
5. The wizard must not present private mode as the safe choice and the default
   as the unsafe one. It states facts: what runs, and where.
6. `ModelDownloadService.start` is no longer called from `WelcomeWizard`.

## 8. Acceptance criteria

Each is independently testable.

1. On a fresh install on a device with the on-device system recognizer,
   `PREF_GOOGLE_VOICE` reads `true`, the mic key produces text, and
   `ModelDownloadService` has never been enqueued.
2. On an upgrade from a build that predates this change, where WaveKey model
   packs are installed, `PREF_GOOGLE_VOICE` reads `false` after first launch,
   and dictation still uses Parakeet.
3. On a device whose only recognizer is the network one, capturing audio is
   blocked until the §3.3 consent sheet is accepted; declining it leaves no
   audio captured and no session started.
4. Setup completes without any WaveKey download being enqueued, on every device
   configuration in §4.
5. The Voice screen shows exactly one private mode control, and no top-level
   engine radio pair.
6. With neither pack installed and both installable, the control reads
   980 MB, and that figure equals the sum of the bundle packs' `totalBytes`
   formatted by `ByteSize`.
7. On a device where `refinerAbiSupported` is false, the control reads 482 MB,
   offers only the recognizer, and reports **On** once the recognizer alone is
   installed.
8. Tapping Go private on a metered connection with no prior consent produces
   exactly one dialog, which states both the size and the mobile-data question;
   accepting it enqueues with `allowMetered = true`.
9. Tapping Go private while offline produces the Queued state with no progress
   bar and a line saying it is waiting for a connection.
10. During Downloading, the on-screen percentage and the notification
    percentage are derived from the same combined bundle total and never differ
    by more than one percentage point.
11. One Cancel during Downloading stops every bundle pack: no WorkManager work
    for any bundle pack remains enqueued or running afterwards.
12. Cancelling after the recognizer has installed but before the refiner has
    lands in Partly on, with dictation on Parakeet, refinement on Nano, and a
    **Finish private mode — 498 MB** action.
13. A download failure on one pack leaves the other pack's installed state
    intact, surfaces one cause mapped from `InstallError`, and leaves the
    keyboard able to dictate on the default engine.
14. Retry after a failure re-enqueues only the packs that are not installed.
15. When the recognizer pack reaches `Installed`, `PREF_GOOGLE_VOICE` becomes
    `false` without further user action, and a session already in progress
    finishes on the backend it started with.
16. Private mode reports **On** if and only if every pack in the current
    language's bundle is installed and selected.
17. Turning private mode off flips the engine preference back, deletes nothing,
    and leaves both packs installed.
18. The turn-off confirmation offers Keep models as the default action and
    Delete as a distinct secondary action; choosing Delete removes exactly the
    bundle packs and nothing else.
19. Turning private mode back on with both packs present completes with no
    network request and no dialog.
20. The Voice models screen still offers download, cancel, remove and import
    per pack, and its header state agrees with the Voice screen's control for
    every combination of installed packs.
21. The IME process still has no `INTERNET` permission and the existing
    permission audit test passes.
22. No AICore or system-recognizer feature download is ever counted in private
    mode's size or progress figures.
23. Every state in §5.3 is reachable in a manual QA pass and renders one
    headline, one action set, and no contradictory secondary text.
24. `WelcomeWizard` contains no call to `ModelDownloadService`.

## 9. Non-goals

1. No new cloud service, account, API key or WaveKey-operated endpoint. The only
   off-device processing is the platform's own speech service and AICore, both
   already on the user's phone.
2. No `INTERNET` permission in the IME process, and no relaxation of the audit
   test, for any reason in this spec.
3. No streaming or partial-text work. Parakeet stays one-shot; routing the
   platform recognizer's partials into the text field is a separate TODO item
   and out of scope here.
4. No change to the deterministic `:core` cleanup pipeline, its settings, or the
   refinement journal.
5. No new model packs, no multilingual recognizer, no change to `ModelCatalog`
   entries beyond how the bundle is derived and how `required` is interpreted.
6. No per-app or per-field policy ("private mode in my banking app only").
7. No resumable or segmented download engine. Resume behaviour is whatever
   `PackInstaller` and WorkManager already give us.
8. No telemetry beyond the existing opt-in, content-free counters.
9. No pause-and-resume-later control. Pause is not a state in §5.3: the states
   are Queued, Downloading and Cancel, because WorkManager's constraint-based
   holding already covers every case a pause button would, and a second
   mechanism would be a second source of truth.
10. No visual design, copy strings, animation or layout decisions. Those are
    `specs/product/PRIVATE_MODE_UX.md`.

## 10. Open questions

1. **Refiner preference when both Nano and Qwen are present.** Spec'd: Qwen
   wins, because private mode's promise is WaveKey's own models. If measurement
   shows Nano is materially better on flagship devices, the better answer may be
   to prefer Nano for quality and keep Qwen as the private-mode guarantee, which
   would need a third refiner state in §6's status line.
2. **Whether the recognizer pack should stay `required = true`.** Spec'd: it
   stays, with `required` re-read as "required for on-device dictation". The
   cleaner change is a new `bundle` concept in `ModelCatalog` and `required`
   deleted, but that touches `ModelReadiness`, `ModelCatalogTest` and the
   installer's semantics, so it is a follow-up rather than a prerequisite.
3. **Delete-on-revert default.** Spec'd: keep. A device that is tight on storage
   may prefer the opposite, and the storage-pressure path (Android's own
   "free up space") is not modelled here.
4. **First-run consent for the network recognizer on a skipped wizard.**
   Spec'd: a one-time sheet on first mic press. If that proves to be the most
   common path, the honest fix may be to refuse the network recognizer entirely
   until the user has seen the setup screen.
5. **Language bundles.** Spec'd: the bundle follows the active subtype's
   language. A multilingual user switching subtypes can therefore be private in
   English and not in French, and how the single control reports that across
   subtypes is unresolved.
