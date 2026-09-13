# WaveKey — plan of record

Architecture, decisions and constraints. **No task state lives here** — that is
[TODO.md](TODO.md).

Started 2026-08-31. Supersedes the VBoard V2 plan
(`~/dev/projects/VBoard/PLAN.md`), which is retired: VBoard's hand-rolled
`app/keyboard/` could never reach Gboard parity, and everything below assumes it
is thrown away rather than ported.

---

## 1. What this is

A Gboard clone — the *typing* half taken wholesale from a maintained AOSP
LatinIME fork — with VBoard's on-device voice intelligence grafted on as the
differentiator. We are not writing a keyboard. We are writing a voice layer and
mounting it inside one that already works.

**Base: [HeliBoard](https://github.com/HeliBorg/HeliBoard) 4.1** (9f5bb63,
2026-08-30). Same AOSP LatinIME ancestor Gboard itself was built from; ~33k LOC
Java (the LatinIME inheritance, incl. the JNI decoder) + ~32k Kotlin (the
modernization), compileSdk 36, minSdk 21.

### Why HeliBoard and not the alternatives

| Candidate | Verdict |
|---|---|
| **HeliBoard** | Chosen. Already implements the interaction we are chasing — see §2. Actively maintained (commits this week). Glide typing, emoji panel, clipboard history, themes, one-handed mode, 100+ layouts all present. |
| AOSP LatinIME direct | Rejected. Apache-2.0 and would let us stay proprietary, but it is the ~2015 drop: NDK decoder, Gradle, edge-to-edge and API-35 behaviour all need rebuilding. HeliBoard spent ~2,500 commits doing exactly that; redoing it buys a license, not a product. |
| FUTO Keyboard | Rejected as a base. Closest existing thing to our product (LatinIME + on-device voice), but Source First 1.1 — non-commercial, modification for personal use only. Legally unusable. Still worth *reading* for how they mounted voice. |
| FlorisBoard | Rejected. Still beta with no word suggestions or spell check, and its own design language — the opposite of a Gboard clone. |
| Keep VBoard's keyboard | Rejected. 3.6k LOC of `app/keyboard/` with no glide typing, no dictionaries, no full emoji palette. "Exact clone" is not reachable from there. |

---

## 2. The interaction we are cloning, and why the base already has it

The brief: *toolbar and suggestions blended in one space at the top of the
keyboard; mic on the right of that row; activating it swaps the row into voice
controls — back, minimize keyboard, done.*

HeliBoard's `res/layout/strip_container.xml` is a `FrameLayout` that already
swaps **three** views through that one row:

- `SuggestionStripView` — itself a single `LinearLayout` holding the toolbar
  expand key, a `toolbar_container`, and the suggestion words. Toolbar keys can
  be *pinned* so they persist onto the normal suggestion strip. This **is** the
  blended row, shipped and working.
- `emoji_tab_strip` — the row when the emoji panel is open.
- `clipboard_strip` — the row when the clipboard panel is open.

**The core architectural bet of this project: voice is the fourth mode of that
row.** We are not adding a bar. We are adding a `VoiceStripView` sibling and a
state transition, which is precisely the Gboard model and costs us the layout
work rather than the interaction design.

### Decision: no separate voice bar

VBoard's `VoiceBarView` is not ported. It existed because VBoard had no strip to
mount into. Mounting into `strip_container` instead is what makes the result
read as Gboard rather than as a keyboard with a voice accessory.

---

## 3. Constraints

### 3.1 GPL-3.0 — accepted, and it binds everything

HeliBoard is GPL-3.0-only (with Apache-2.0 and CC-BY-SA-4.0 for inherited
parts). WaveKey ships as GPL-3.0. **The voice intelligence carried over
from VBoard is published with it** — `core/`, the ASR session logic, the
refiner. That was decided knowingly on 2026-08-31; it is not revisitable
per-file. Upstream `LICENSE*` files stay, attribution to HeliBoard and to AOSP
stays, and every source file we modify keeps its SPDX header.

### 3.2 Stay rebaseable on upstream

This fork lives or dies on being able to pull HeliBoard's fixes. Therefore:

- **Do not rename the `helium314.keyboard` package namespace.** Only
  `applicationId`, app label and icon change. A namespace rename touches every
  one of 65k lines and makes every future merge a conflict.
- New code goes in **new files and new Gradle modules**, not inside upstream
  files, wherever that is possible at all.
- Edits to upstream files are surgical and commented with why. No reformatting,
  no drive-by refactors, no style migrations. An upstream file we reformatted is
  an upstream file we can no longer merge.
- `upstream` remote is configured from day one and rebased on deliberately, not
  incidentally.

### 3.3 The permission departure

HeliBoard's identity is that it requests **no INTERNET and no RECORD_AUDIO**.
We must break both. This is the single largest deviation from the base and is
stated up front rather than discovered by a user:

- `RECORD_AUDIO` — the IME process. Unavoidable; it is the product.
- `INTERNET` — **the `:ui` process only**, for model download. The IME process
  must never hold it. Enforced by manifest process attributes and audited.

### 3.4 Inherited from VBoard, non-negotiable

- **Never log user content.** No transcript, no clipboard text, no typed text in
  any log, crash report or metric, in any build type.
- The `:llm` refiner runs **out of the keyboard process**. A 0.5B model OOM must
  not take down the user's ability to type in every app on the device. VBoard
  landed this (Wave 0.5) and the reasoning is unchanged.
- Typing-only memory budget ≤60MB in the IME process.

---

## 4. What comes across from VBoard, and what does not

| VBoard | LOC | Disposition |
|---|---|---|
| `core/` (text, session, suggest, correct, clipboard, model, keyboard) | 15.9k | **Ported whole.** Pure Kotlin, zero Android deps, only coroutines, no `app` imports — it drops in as a `:core` Gradle module unchanged, tests and all. This is the asset the whole project exists to keep. |
| `app/voice/` (AsrEngines, VoiceSessionController) | 1.9k | Ported into a new `:voice` module. Android-dependent but IME-agnostic. |
| `app/models/` (download, storage, lifecycle) | 1.0k | Ported into `:voice`. |
| `app/correct/` (AiFixController) | 0.6k | Ported; re-mounted on a pinned toolbar key. |
| `app/llm/` + `ILlmRefiner.aidl` | 0.3k | Ported as the `:llm` module/process, boundary intact. |
| `app/settings/`, `app/onboarding/` | 2.0k | **Partially.** HeliBoard has its own settings and setup wizard; we add voice/model screens into it rather than carrying VBoard's. |
| `app/keyboard/` | 3.6k | **Deleted.** Replaced by HeliBoard's keyboard. |
| `app/ime/VBoardImeService.kt` | 1.2k | **Deleted.** Replaced by `LatinIME.java`; the voice hooks are re-attached to it. |

Net: ~19.7k LOC of intelligence kept, ~4.8k LOC of keyboard discarded.

---

## 5. Open questions

- **Where does `:core` sit relative to the native decoder?** HeliBoard's
  suggestions come from a C++ JNI decoder (`jni/src/suggest`). Our
  `core/suggest/SuggestionEngine` was written for a keyboard with no such thing.
  These have to be composed, not merged — the native decoder stays authoritative
  for typing; ours contributes for dictated text. Settled in W4, not before.
- **Does Gboard keep the keyboard visible during dictation?** It does, with the
  row swapped and a mic indicator; verify against a real device before building
  W3 rather than from memory.
- **Two-model confidence (VBoard W1.1) is unproven** and stays unbuilt here
  until the alignment/normalization foundation exists. It is carried into
  TODO.md at the bottom, not the top.

---

## Revisions

_(append below; do not edit decisions above in place)_

### R1 — 2026-08-31: git history is kept, not squashed (amends W0.1)

TODO W0.1 called for dropping HeliBoard's history to a single squashed base
commit. We kept the full history instead and renamed the `origin` remote to
`upstream`. Reason: §3.2 makes rebaseability on upstream the fork's survival
condition, and a squashed base makes every `git rebase upstream/main` re-derive
merge bases it could otherwise read directly. The base SHA anchor W0.1 wanted is
still recorded — `9f5bb63` — in the fork's first commit message and in README.
Nothing about the fork's licensing or attribution changes.

### R2 — 2026-08-31: rebrand scope (W0.2)

`applicationId` is `com.supervoiceboard.app`; the `helium314.keyboard` namespace
is untouched, per §3.2. Also changed, because they are global identifiers that
would otherwise collide with an installed HeliBoard: the two content-provider
authorities (`clip_provider.xml`, `gesture_data.xml`). The launcher icon keeps
HeliBoard's adaptive-icon structure and gradient geometry with new colors and a
new foreground mark, so it stays a derivative work under CC-BY-SA-4.0 and the
attribution stays. Only the *base* `values/strings.xml` was rebranded; the ~100
translated `values-*/strings.xml` still say "HeliBoard Spell Checker" in their
own languages and are deliberately left for upstream to carry.

### R3 — 2026-08-31: `:core` keeps the `com.vboard.core` package (W1.1)

The module is ported verbatim, package names included. Renaming to
`com.supervoiceboard.core` would touch all 78 files and every test on the way
in, which is exactly the diff W1.1 exists to avoid — the point of the gate is
that the tests pass *unchanged*. The Gradle module is `:core` and its build file
is rewritten (WaveKey has no version catalog; versions are literal).
A rename, if wanted, is a separate mechanical commit after the module is wired.

### R4 — 2026-08-31: W1.2 and W1.3 arrived already fixed; closed with tests

VBoard's TODO listed both `ClipClassifier` defects as open, but the source we
ported already carries the fixes: `DIGIT_RUN_PATTERN`'s separator class is
`[\p{Zs}\p{Pd}]` and the card rule reads the invisible-stripped text. What was
missing was coverage, so the fix could silently regress. Regression tests now
pin both shapes — NBSP/narrow-NBSP/thin-space/en-dash/em-dash grouping, a card
carrying ZWSP/ZWNJ/BOM/soft-hyphen, and cards and OTPs written in Arabic-Indic
digits.

Writing them found one real gap: U+2212 MINUS SIGN is category Sm, not Pd, so a
card grouped with it evaded Luhn. It is now listed explicitly in the separator
class. This is the only change to ported `:core` logic besides W1.4.

### R5 — 2026-08-31: VB-QA-05 idempotency closed (W1.4)

`clean("scratch that scratch that")` used to render the text "Scratch that";
cleaning that output fired SCRATCH_THAT, and on the VB-124 double-cleanup
fallback path that deletes an utterance the user already committed. The
utterance command is now re-detected after the repetition-collapse stage, gated
on `repetitionsCollapsed > 0` so that a stage-3 spoken-punctuation conversion
("scratch that period") can never turn dictated words into a command. The
`@Disabled` test in `CleanupPropertyTest` is enabled and `QaRegressionPinTest`'s
pin is inverted to assert the fixed behaviour. `:core` now has 795 tests, 0
failures, 0 ignored.

### R6 — 2026-08-31: `:voice` uses the namespace `com.vboard.app` (W2.1)

The ported sources reference `com.vboard.app.R` for their strings. Giving the
`:voice` library that namespace makes those references resolve with no edit, so
the diff against VBoard stays readable. `:llm` is `com.vboard.app.llm`.

### R7 — 2026-08-31: voice settings live in HeliBoard's preferences (W2.5)

VBoard kept settings in a DataStore of its own, and its snapshot carried the
keyboard's settings too — theme, haptics, key preview, autocorrect mode, number
row, clipboard history. All of those are HeliBoard's here, so the ported
`SettingsRepository` was cut down to what the voice layer alone decides and
re-pointed at HeliBoard's SharedPreferences. One store, one screen, and the
dictation path keeps a synchronous read.

### R8 — 2026-08-31: what "INTERNET only in `:ui`" can actually mean (W2.3)

Android grants permissions to an application, not a process: there is no way to
declare INTERNET for `:ui` alone, and any claim otherwise would be theatre. What
is enforceable is that every component that can reach the network runs in `:ui`
— the download service and WorkManager's foreground service, with WorkManager's
androidx.startup initializer removed so the keyboard process never hosts the
downloader — and that the IME declares no `android:process` at all.
`ManifestProcessSplitTest` asserts exactly that, and the manifest carries the
`supervoiceboard:internet-scoped` marker the CI audit greps for.

HeliBoard's own settings activity stays in the main process. Moving it to `:ui`
would put its SharedPreferences writes in a different process from the IME's
reads, which SharedPreferences does not support; it touches no network, so it
does not need to move.

### R9 — 2026-08-31: MediaPipe's minSdk is overridden, not adopted (W2.4)

`tasks-genai` declares minSdk 24 and HeliBoard supports 21. Raising the floor
would drop API 21-23 users for an optional feature, so `:llm` force-merges the
library and both `LlmRefinerService.engineOrNull` and `refinerClientOrNull`
return null below API 24. On those devices the refiner simply does not exist.

### R10 — 2026-08-31: `VoiceRuntime` replaces `VBoardApp` (W2.1)

VBoard handed its `Application` subclass to the session controller, the download
worker and the refiner client. The hosting Application here is HeliBoard's — an
upstream class this fork does not own — so those five references now go through
`VoiceRuntime` / `VoiceRuntimeHost` (and `RefinerModelHost` for the `:llm`
process, which must not see the keyboard's half at all). `App.kt` implements
them; that is the entire footprint of the voice layer in an upstream file.

`VoiceBarView` was not ported, per §2, so `ErrorActionKind` moved out of it and
into `VoiceErrorAction` in `:voice`: which error happened and what the recovery
is are session facts, not view state.

### R11 — 2026-08-31: HeliBoard's Robolectric tests need Java 21

`:app`'s inherited tests (InputTest, ParserTest, SpellCheckerTest, …) fail
before they run on Java 17: Robolectric refuses to sandbox an SDK 36 target
below Java 21. CI now uses Java 21 so they actually execute. This machine has
only JDK 17 (AGP 8.13 rejects the JDK 25 in Android Studio), so `:app`'s
Robolectric tests are unverified locally and are verified in CI instead;
`:core`'s 795 tests and the plain-JUnit manifest audit run fine on 17.

### R12 — 2026-08-31: what W3.1 actually observed

Checked against Gboard running on an Android 15 emulator, not a physical device
— the fork has none available. Screenshots are in `docs/reference/`. What it
does during dictation:

- The keyboard **stays fully visible** and the keys stay live. There is no
  full-screen takeover and no separate bar.
- The strip row becomes: back arrow at the left, a centred status line
  ("Listening…"), and the mic at the right in an active filled-circle state.
- Idle, the mic sits at the right-hand end of that same row, outside the
  scrolling toolbar, and stays there in every state of it.

`VoiceStripView` mirrors that, and adds two things Gboard does not show: a level
meter behind the status text (silence and a dead mic are otherwise
indistinguishable) and an explicit done control, so a session can be ended
without waiting for endpointing.

### R13 — 2026-08-31: the mic is not a pinned toolbar key (W3.2)

HeliBoard can pin toolbar keys onto the suggestion strip, but pinned keys are
hidden whenever the toolbar is expanded, and the brief calls for a mic that is
always there. It is therefore its own view at the end of
`suggestions_strip.xml`, a sibling of `pinned_keys`. It follows the existing
"show voice key" setting, so a user who turned the voice key off does not get
one anyway.

### R14 — 2026-08-31: the VOICE key no longer reaches the system IME (W3.6)

`LatinIME.onEvent` called `mRichImm.switchToShortcutIme(this)` for
`KeyCode.VOICE_INPUT`. It now calls `toggleVoiceInput()`. HeliBoard's other
handling of that key code — shift state, keyboard switching, the popup-key
shortcut — is untouched.

### R15 — 2026-08-31: `:core`'s SuggestionEngine does not run (W4.1)

The open question in §5 is settled: **HeliBoard's native decoder is the only
suggestion engine in this fork, for typing and for dictation alike.**
`core/suggest` — SuggestionEngine, Lexicon, UserHistory — stays in the tree,
compiled and tested, and is wired to nothing.

Why not compose them:

- The native decoder is what makes this a Gboard clone rather than a keyboard
  with a word list: it has the dictionaries, the gesture decoding, the
  personalization, 100+ locales. `core/suggest` was written for a keyboard that
  had none of that, against an English lexicon.
- Two engines writing the suggestion strip means one of them must win per
  keystroke, and that decision has no principled answer at the strip. Every
  arrangement we sketched — ours for dictated spans, native for typed ones —
  needs the strip to know which characters came from which source, which the
  input connection does not tell us after a commit.
- Dictated text is not where suggestions matter. What dictation needs from
  `core/` is the *cleanup* pipeline (TranscriptCleaner, CommitPlanner,
  ContentGuard) and that is wired, in W4.2. Correction of dictated text is the
  refiner's job (W5), not a second n-gram engine's.

`core/suggest` is kept rather than deleted because `AiFixController` (W5.1) uses
its lexicon for the non-LLM fallback path, and because deleting 4k lines of
tested code to prove a point is not a decision that has to be made now. If W5
lands without needing it, deleting it is a clean follow-up.

### R16 — 2026-08-31: ContentGuard wraps the cleaner, not the commit (W4.2)

TODO W4.2 lists the pipeline as "TranscriptCleaner → CommitPlanner →
ContentGuard". The implemented order is ContentGuard.shield → TranscriptCleaner
→ ContentGuard.restore → CommitPlanner.joinForInsertion, because that is what
the guard is for: the tokenizer drops symbols it does not recognize, so the
shield has to be in place *before* the cleaner runs, and lifted after. Running
it last would have nothing left to protect. `endsWithShieldedSpan` also
suppresses the terminal period, so an utterance ending in a URL keeps it intact.

### R17 — 2026-08-31: the fix controller talks to a `FixSurface` (W5.1)

`AiFixController` drove VBoard's own `ToolbarView` directly. Here the button is
one of HeliBoard's toolbar keys, so the controller drives a `FixSurface`
interface instead: it decides what to say and which state the key is in, and
`AiFixKey` in `:app` decides how a key and a message look in this keyboard.
`AI_FIX` is a real `ToolbarKey` with its own `KeyCode`, so it can be enabled,
reordered, pinned or removed with every other toolbar key. It ships enabled and
pinned by default — a key nobody can find is a feature nobody has.

Messages go through HeliBoard's existing toast surface rather than a message
line of our own, because that is the one users of this keyboard already know.

### R18 — 2026-08-31: attribution is on the long-press (W5.2)

The user is owed an account of what the model rewrote, but not a permanent panel
for it: the fix is usually mechanical, and a UI that reports "nothing changed"
most of the time trains people to ignore it. Long-pressing the fix key lists the
**editorial** edits — the model substituting or rewording — as `"before" → "after"`
lines. Mechanical edits (casing, spacing, a doubled word) are deliberately not
listed: they are visible at a glance and attributing them would bury the changes
that matter.

## W6 measurements — 2026-08-31

Measured on an Android 15 emulator (arm64), release build, R8 on.

**IME process memory, typing only: 40.1 MB PSS** (budget ≤60MB, §3.4). Measured
with `dumpsys meminfo` while typing into a search field with no voice session
started; only the keyboard process existed — `:ui` and `:llm` had never been
spawned, which is the point of the split.

**APK size**: 88 MB universal, **37 MB arm64-v8a**, 35 MB armeabi-v7a, 39 MB
x86/x86_64. HeliBoard's own build is ~21 MB; the difference is ~31 MB of native
code per ABI from sherpa-onnx (ONNX Runtime) and MediaPipe. Release builds are
therefore split per ABI (W6.2), so a phone downloads one architecture. Voice
models are not in the APK at all — they are downloaded.

### R19 — 2026-08-31: privacy audit findings (W6.1)

The fork's own code was clean: every log line in `:voice`, `:llm` and
`helium314/keyboard/voice` carries ids, counts, durations or enum names, never
text. HeliBoard's inherited code was not, and its `Log` wrapper keeps an
in-memory buffer that the about screen can export to a file, so a debug-gated
log line is still a line that leaves the device. Ten call sites were rewritten
to log a length or nothing at all: the committed word and its ngram context in
`InputLogic`, the composing-text read-back in `RichInputConnection`, the
normalized-score line in `AutoCorrectionUtils`, and contact/app names plus
dictionary words in `AppsBinaryDictionary`, `ContactsBinaryDictionary` and
`ExpandableBinaryDictionary`.

### R20 — 2026-08-31: hold-to-talk, raw hold, and what W6.5 became

Tap-to-toggle is unchanged; a press held past 350ms becomes hold-to-talk and the
release sends (W6.3). Holding past 1.2s escalates to raw dictation for that
session only — no cleanup, no refinement, no setting touched (W6.4).

W6.5's "adaptive endpointing" is re-scoped to exactly this: while the key is
held, a silence endpoint is ignored, because the finger is a better endpoint
signal than any threshold — a user pausing mid-sentence with the key down is
thinking, not finished. Tap-started sessions keep the existing 0.8s/2.4s rules,
and the recognizer's hard length cap still applies in both, so a stuck key
cannot record forever. No learned or per-user thresholds: that would need
measurement we have not done, and this gets the benefit without it.

### R21 — 2026-08-31: three releases shipped a stale APK

`assembleRelease` had been failing at `lintVitalRelease` (a manifest `<service>`
entry for `ModelDownloadService`, which is an object, not a Service) and at R8
(MediaPipe's AutoValue/protobuf annotations needed `-dontwarn`). The release job
piped gradle through `tail`, so the pipeline's exit status was `tail`'s and the
failure was invisible; v0.2.0-w2, v0.3.0-w3 and v0.4.0-w4 were published with the
W0 baseline APK attached. Those assets have been deleted and each release now
says so. Both build failures are fixed, and release builds are checked by exit
status rather than by reading the tail of a log.

### R22 — 2026-08-31: W7.1 is built as a foundation and left unwired

TODO W7.1 gates two-model confidence on measured disagreement precision against
a hand-labeled 200+ utterance corpus, published either way, and cancels it below
~70%. **That corpus does not exist and cannot be produced here** — it needs real
recorded speech and human labels, not code. So the gate is unmet, and the
feature is not shipped: nothing marks a word in the UI, and no confidence reaches
the strip.

What did land is the foundation §5 said the feature had to wait for, as pure
`:core` code with tests: `TranscriptAlignment` normalizes two transcripts of the
same speech, aligns them word by word (Levenshtein backtrace), and reports which
committed words the two models disagree about, plus a disagreement rate.
Punctuation and case differences are normalized away first, because two
recognizers differing about a comma is not disagreement about words.

Whoever picks this up needs the corpus, not more code. Until then this is dead
code that compiles and is tested, which is the honest state.

### R23 — 2026-08-31: spoken formats run first, and refuse when unsure (W7.2)

`SpokenFormats` runs on the raw transcript, before `ContentGuard` shields it —
"five dollars fifty" has to be "$5.50" *before* the guard has something to
protect. Raw mode is exempt, since that is the verbatim escape hatch.

Every rule needs an unambiguous trigger: a currency word preceded by a number, a
clock pair with a meridiem, an explicit "dot"/"at" chain ending in something
TLD-shaped. "Three thirty" with no meridiem, "a pound of flour", "connect the
dots" and "meet me at the pub" are all left exactly as spoken. The failure modes
are asymmetric — leaving a spoken form alone is an annoyance, rewriting prose is
the keyboard putting words in the user's mouth.

Number matching is built from the vocabulary rather than `[a-z]+`; the generic
form matched "costs twenty" as an amount and swallowed "fifty cents" into the
fraction.

### R24 — 2026-08-31: telemetry is opt-in, content-free, and stays on the device (W7.3)

`VoiceMetrics` records exactly two things: how many dictated utterances were sent
without editing, and how long each took from mic press to committed text. The
type cannot hold text — there is nowhere to put it — and the snapshot carries
aggregates only, not a per-utterance list, since a sequence of durations
fingerprints a session in a way a mean does not.

It is off by default, behind a switch in the voice settings, and the switch's own
summary shows what was measured — whoever is asked to turn measurement on gets to
see the result. **Nothing is transmitted.** There is no endpoint and no file: the
numbers live in the IME process and die with it. "Opt-in telemetry" that sent
data anywhere would need a privacy policy, an endpoint and a consent flow this
fork does not have, and shipping the collection without them would be the wrong
half to build first.

### R25 — 2026-09-01: model management, and the optional setup step

The download machinery existed since W2 but nothing drove it. It now has a UI:

- **Voice models screen** (Settings → Voice typing → Voice models): one row per
  pack with its size and state, and whichever action that state allows —
  Download, Cancel while one runs, Remove when installed, Import otherwise. It
  reads disk state rather than only the live flow, because the flow is empty
  after process death and a pack would otherwise offer "Download" for a download
  already running.
- **Setup wizard**: an optional voice row on the last step, next to Finish. It
  names the size and leads to the models screen; setup completes without it.
  Offered last and never gated on, because dictation costs several hundred
  megabytes and a keyboard must be usable before that is spent.

**Import** is a source, not a second install pipeline: `PackInstaller.importFile`
streams the user's file into the same staging directory a download writes to,
verifies it against the same catalog digest, and lets the normal `install` path
extract and finalize it. The digest is checked at import rather than at install
because that is where the user can still act on the answer — after staging, an
unverified file is indistinguishable from a resumed download. A file whose bytes
do not match is refused and nothing is left behind, since a surviving half-file
would later be resumed as if it were a download.

Removing a pack deletes its installed files, its partial downloads and its older
versions, and the row goes back to offering Download and Import.

### R26 — 2026-09-01: models live in internal storage, not `Android/media`

VBoard put model packs in `Android/media/<pkg>` so an uninstall would not take a
gigabyte with it. That is the wrong trade for a keyboard, and testing this fork
showed why: the IME is `directBootAware` and the system starts its process
before the device is unlocked, and a process started that early does not get a
usable view of external storage. It can list the models directory and still not
see files another process wrote into it — so the mic reported "voice models
aren't downloaded yet" for models that were demonstrably installed, with the
settings screen showing them as Installed at the same time.

`ModelRoots.choose` now prefers device-protected internal storage, which every
process of this app can read at every point in the boot, and only reads the
external root when packs are already there from an older build.

Two bugs fell out of that switch and are fixed with it:

- The internal root was never created, and a directory that does not exist
  reports **zero** usable space — so the installer's storage pre-check failed
  instantly and every download died before it started, silently.
- The models screen believed the download worker's last published state over
  what is on disk, so a pack the keyboard could not read still read "Installed"
  there. Disk wins now, except while work is actually scheduled.

`ensureExtracted` also logs when a pack is not installed under the root it
looked in — the case above was silent, and "not installed" and "installed
somewhere this process cannot read" looked identical from the outside.


### R27 — 2026-09-01: W7.1 is cancelled; the answer is the accurate model's, and latency is the goal

Superseding R22. The two-model confidence idea is dropped at the user's
direction: the committed text is simply the accurate model's answer, and no
disagreement signal is computed, marked or shown. `TranscriptAlignment` and its
tests are deleted rather than left as dead code with no future — the git history
has them if the idea ever comes back, and a repository is not a museum.

What replaces it is the thing the feature was competing for: **the time between
speaking and having the final text.** Four changes, in the order they pay off:

1. **Provisional commit.** At the endpoint the live model's cleaned text is
   committed immediately and replaced in place when the accurate model's answer
   arrives a second later. The text the user keeps is still the accurate model's
   — this only decides whether the wait happens with an empty field or a
   readable one. It can be turned off ("Show text immediately"), and an
   utterance that turns out to be a command takes its provisional text back
   before acting on it.
2. **Warm on touch-down.** Model load is the largest single component of a cold
   press, and the tap gesture is free time: the engines start loading on
   ACTION_DOWN, before the tap has even completed.
3. **Engines stay loaded while the keyboard is visible.** Releasing them between
   two dictations in one sitting was the slowest path there is.
4. **No silence wait while the mic key is held** (W6.5, already landed): the
   finger is the endpoint.

The 0.8s / 2.4s endpoint thresholds are deliberately left alone. Shortening them
would cut latency for tap-to-toggle too, and would also cut people off
mid-sentence; that trade needs measurement this fork has not done, and the hold
path already avoids the wait entirely.


### R28 — 2026-09-01: `:core` compiles against Java 11 and runs on Android

The keyboard reported "voice models aren't downloaded yet" for models that were
downloaded, extracted and visible in settings. The cause was one line:

    Files.readString(marker).trim().toInt()

`:core` is a plain JVM library module, so it compiles against JDK 17 and
`Files.readString` resolves. Android's `java.nio.file.Files` has no such method,
so at runtime it threw `NoSuchMethodError` — **inside a `runCatching`**, which
catches `Throwable`. Every installed pack therefore read as `NotInstalled`, and
the failure produced no log, no error state and no user-visible cause. It now
uses `Files.readAllBytes(...).decodeToString()`.

Two things follow from this, beyond the one-line fix:

- **The module boundary hides this class of bug.** `:core` is compiled as a JVM
  library precisely so it can be tested without an Android runtime, and that is
  worth keeping — but nothing checks that what it calls exists on Android. Any
  Java 9+ API added to `:core` needs that check by hand until something
  automates it. The same applies to the `:voice` module's compile: the Android
  compiler *did* reject `Files.readString`, which is how this was finally found.
- **`runCatching` around a platform call is a trap.** It swallows `Error` as
  well as `Exception`, so a missing method looks exactly like a corrupt file.
  Where the two need different answers, catch `Exception`.

R26's storage change stands on its own merits — a directBootAware IME should not
keep its models in credential-encrypted storage — but it was not the cause of
this, and the note there overstated the evidence.

## R28 — continuous AI fix reads the whole field, on purpose

`AiFixController.performFix` reads and rewrites the entire field on every pass,
and `scheduleAutoFix` fires at each word boundary once a run is armed. TODO
carried a proposal to narrow that to the changed word or sentence.

Rejected. Words are not independent: a sentence's last word routinely decides
the tense, the article or the comma three words back, and a pass that can only
see what just changed cannot make that edit. The value of the feature is that it
reads what the user actually wrote, all of it.

What this accepts: a long message costs a full-field pass per word typed, and
every pass can move the cursor. If that becomes the complaint, the answer is a
longer idle before the pass and a diff-apply that leaves untouched spans alone —
not a smaller window of text for the model to read.

