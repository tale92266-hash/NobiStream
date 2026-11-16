# NobiStream

NobiStream is an Android app that lets you sign in to your own Telegram account and watch videos from a Telegram group or channel — paste a group/channel link, see all its videos in one list, filter by quality (1080p / 720p / etc.), and play them with YouTube-style streaming, buffering and cache, with resume (last watched position) and Next / Previous navigation. It uses Telegram's official MTProto client library (TDLib) with your own login — no bots, no scraping.

## Features (approved)

- Sign in with your own Telegram account (phone number + Telegram code)
- Paste a Telegram group/channel link → all its videos in one folder/list
- Quality filter parsed from post text (1080p, 720p, 480p, 360p, …) — only the quality you choose is shown
- Smooth video playback with streaming, buffering and cache (Media3 / ExoPlayer)
- Resume: last watched position is remembered
- Next / Previous video navigation

## Stack

| Component | Version |
|---|---|
| Gradle | 8.11.1 |
| Android Gradle Plugin (AGP) | 8.9.2 |
| Kotlin | 2.2.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Media3 (ExoPlayer) | 1.11.1 |
| Room | 2.8.5 |
| TDLib | 1.8.64 via `io.github.tdlib-android:core/ktx:0.1.1` (Maven Central, prebuilt) |
| Coil | 2.7.0 |
| Kotlin coroutines | 1.11.0 |

## TDLib license

TDLib is licensed under the **Boost Software License 1.0**. See the TDLib repository for the full license text.

## How to build

**On your phone (ACS):** copy the project folder to your phone, open it in ACS (Android Code Studio), press Run. This builds a complete debug APK.

**GitHub Actions:** push to `main` — the workflow `.github/workflows/android-debug.yml` builds a complete debug APK (no R8, no release build) and uploads it as an artifact named `nobistream-v1-debug-apk` (kept 30 days). Download it from the Actions run.

## Signing (fixed key, install-over updates)

The signing keystore is a fixed key so every build installs over the previous one. The keystore file itself lives at `~/workspace/NobiStream-signing/nobistream.p12` (alias `nobistream`) and must **never** be committed. The password is in `~/workspace/NobiStream-signing/nobistream.p12.pass.txt` next to it.

For signed CI builds, create these four secrets in the GitHub repo (Settings → Secrets and variables → Actions). Base64-encode the keystore file first: `base64 -w0 nobistream.p12` (on Linux) and paste the output as the secret value.

| Secret | Value |
|---|---|
| `NOBISTREAM_KEYSTORE_B64` | Base64 of `nobistream.p12` |
| `NOBISTREAM_KEYSTORE_PASSWORD` | Keystore password (from `nobistream.p12.pass.txt`) |
| `NOBISTREAM_KEY_ALIAS` | `nobistream` |
| `NOBISTREAM_KEY_PASSWORD` | Key password (same as the keystore password) |

If the secrets are not set, the CI build still succeeds — it falls back to the default debug key (unsigned for install-over purposes).

## Not yet verified on device

No on-device test has happened yet. Sam's phone test is the verdict.
