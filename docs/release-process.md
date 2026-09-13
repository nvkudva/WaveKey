# Release process

How a WaveKey build gets from a tag to a page a person can download from.
Everything here is `.github/workflows/release-build.yml` plus the handful of steps
a workflow cannot do for itself.

## What a release is

One tag, one GitHub Release, one asset:

- `WaveKey-<tag>-arm64-v8a-debug.apk`, or `-release.apk` once the repo has
  a keystore.
- No universal APK, no second ABI, no mapping file.

**arm64-v8a only, decided rather than deferred.** A 32-bit device cannot usefully
run a 482 MB recognizer plus a 550 MB refiner, and a second ABI is another full NDK
compile of the native code. The release body and the README both say so in plain
words, so a v7a user learns it before downloading instead of after.

Every release is marked **prerelease**. That flag comes off by hand, and only after
the precondition below is met.

## Publishing

1. Tag the commit with an *annotated* tag. The annotation message is copied into the
   release body verbatim, as its third block — it is the only per-release prose,
   so write it as release notes, not as a commit subject.

   ```bash
   git tag -a v0.10.0 -m "..."
   git push origin v0.10.0
   ```

2. `release-build.yml` fires on `v*`. It assembles arm64-v8a, renames the asset,
   composes the body and calls `gh release create --prerelease`.

3. `ui-qa.yml` fires on the same tag, independently. There is deliberately no
   `needs:` between them: the emulator suite takes up to 60 minutes, and gating the
   publish on it means a tag produces nothing for an hour with a flaky emulator able
   to block a release.

Publish forward. `v0.8`, `v0.8.0-rc.1` and `v0.9.0` are assetless and stay that way
— moving or deleting a published tag rewrites history other clones may hold.

## Why the asset is renamed

`app/build.gradle.kts:121` bakes `versionName` into the output filename, so
`assembleDebug` emits `WaveKey_4.1-debug-arm64-v8a.apk` no matter what the
tag says. The workflow renames it at upload time and the release body's footer says
what the 4.1 is: the HeliBoard base version, `versionCode 4101`, not this release.

The `versionName` / `versionCode` bump is **out of scope for the release pipeline**
and unresolved. It belongs to whoever owns `app/build.gradle.kts`. Do not fix the
mismatch by editing gradle from a release change.

## Signing — the dormant half

The workflow ships working and dormant. With no `WAVEKEY_KEYSTORE_B64` secret it runs
`assembleDebug`; with one it runs `assembleRelease`. The branch is a step-level `if`
on the secret, not a gradle-side fallback — `app/build.gradle.kts:69` guards the
signing config with `storeFile?.let {}`, which makes a keystore-less
`assembleRelease` produce an *unsigned* APK. Unsigned is uninstallable, not a
degraded debug build.

A human generates the keystore and sets three repo secrets. This is the only part of
a release that cannot be automated, and it must not be.

```bash
keytool -genkeypair -v \
  -keystore release.jks -storetype JKS \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias wavekey -dname "CN=WaveKey, OU=Dev, O=WaveKey, C=US"
base64 -w0 release.jks    # paste into WAVEKEY_KEYSTORE_B64
```

| Secret | Constraint |
|---|---|
| `WAVEKEY_KEYSTORE_B64` | base64 of `release.jks`. Setting it is what flips the workflow to `assembleRelease`. |
| `WAVEKEY_STORE_PASSWORD` | Must not be empty, and must not be the literal `supervoiceboard`. |
| `WAVEKEY_KEY_PASSWORD` | Same. |

The alias defaults to `wavekey`; a keystore that uses another one has to say so in
`WAVEKEY_KEY_ALIAS`, which is read from the environment, not from a secret.

The passwords have no default. A release build with either variable unset fails at
gradle's configuration step, naming the variable, rather than producing an unsigned
APK — and the workflow's "Check signing secrets" step exits before gradle is called
at all if either secret is empty or is the literal `supervoiceboard`, which the
first key was created with and which is public in this repo's history.

Keep `release.jks` and its passwords off this machine and out of this repo. Losing
the key means no build can ever upgrade a build signed with it.

### Precondition on the first signed release

`app/build.gradle.kts:61` sets `testBuildType = "debugNoMinify"`, so the UI QA suite
has never run against a minified build. `release` has `isMinifyEnabled = true`. The
day `assembleRelease` starts shipping, an R8-only crash reaches users unverified, and
no CI job covers it.

So: before the prerelease flag comes off a signed build, somebody installs that exact
APK on a real phone and dictates a sentence with it. Not the debug APK, not an
emulator — that APK.

## The one manual step: screenshots

`ui-qa.yml` regenerates two PNGs at each tag, inside the `ui-qa-reports-api34`
artifact under `ui-qa-screenshots/`. They are downloaded and committed **by hand**:

| In the artifact | Committed as |
|---|---|
| `suggestion-row-with-mic.png` | `docs/screenshots/suggestion-row-with-mic.png` |
| `voice-row-active.png` | `docs/screenshots/voice-row-no-model.png` |

The rename is not cosmetic. `KeyboardVoiceFlowTest.kt:93-96` documents its own
precondition: the CI emulator has no 482 MB Parakeet pack, so the session cannot
start and the row comes up in its **error state** — status line and back control,
done and minimize hidden. The file is committed under the honest name and captioned
as the error state. Neither PNG goes in a store listing.

A real dictating-row screenshot needs a speech model on the CI emulator. That is its
own piece of work and does not block a release.

**Do not wire a bot up to commit these.** It needs a write token and a bot commit for
two images that change rarely, and the workflow already regenerates them for a human
to pick up.

## README

The README's `## Install` section links to the Releases page. It is written in the
same change as the first release that actually has an asset — never before, or the
link points at an empty page.

## Not part of this

Each of these is a separate piece of work, and none of them is a prerequisite for a
GitHub release:

- F-Droid and Play submission, the privacy policy, the data-safety declaration. All
  gated on the unverified Hugging Face download (`sha256 = ""`) that the README's
  Status section records.
- Pinning the refiner model hash, and reproducible builds.
- `armeabi-v7a`. Decided against, above.
- `fastlane/` — see `docs/store-listing.md`.
- In-app update checks, a version number in settings, and any in-app link to this
  page. A keyboard that phones home to check a version is a different product than
  the one the README describes.
