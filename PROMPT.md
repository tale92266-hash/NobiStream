# NobiStream — Full Build Prompt (copy-paste for another AI)

> SECURITY FIRST: This prompt contains PLACEHOLDERS for the Telegram API
> credentials, never the real values. Before sending this prompt anywhere,
> replace `<PASTE_YOUR_API_ID_HERE>` and `<PASTE_YOUR_API_HASH_HERE>` with
> your own values ONLY if you trust that destination. Never commit the real
> values to any repo, never print them in logs, and never paste them into a
> public chat. The safe way to use them in a build is GitHub Actions secrets
> (see "Credentials" below), never source code.

## 1. What to build

Build **NobiStream**, a native Android app (Kotlin, English UI only) that lets
the user sign in with their OWN Telegram account and watch videos from a
Telegram group or channel:

1. Paste a Telegram group/channel link (e.g. `https://t.me/channel_name`) —
   all its videos show in one folder/list.
2. Filter the list by video quality parsed from the POST TEXT
   (1080p / 720p / 480p / 4K). Only the chosen quality is shown.
3. Play videos with YouTube-style streaming, buffering and cache.
4. Remember last watched position per video (resume).
5. Next / Previous navigation through the filtered list, with Autoplay.

No bots, no scraping of public web pages (a public t.me page has NO direct
video URL — verified live), no yt-dlp, no downloader websites. The only route
is MTProto with the user's own login.

## 2. Credentials (fill in ONLY at the destination you trust)

- `api_id`: `<PASTE_YOUR_API_ID_HERE>` (number, from my.telegram.org)
- `api_hash`: `<PASTE_YOUR_API_HASH_HERE>` (long string, from my.telegram.org)
- How to get them: log in at `my.telegram.org` → API development tools →
  create an app (App title: `Nobi Stream`, Short name: `NobiStream`,
  Platform: Android, URL: leave empty) → copy api_id and api_hash.
- The app must NEVER hardcode them in source. Inject them at build time via
  GitHub Actions secrets `NOBISTREAM_API_ID` and `NOBISTREAM_API_HASH` as
  BuildConfig fields, AND let the user override them in the app's Settings
  screen (stored in EncryptedSharedPreferences, on the phone only).
- Telegram login inside the app: phone number (+91) → code sent to Telegram
  → 2-step verification password if enabled. Session is saved; login once.

## 3. Tech stack (pinned — do not change versions)

| Component | Version |
|---|---|
| Gradle | 8.11.1 |
| Android Gradle Plugin (AGP) | 8.9.2 |
| Kotlin | 2.2.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Media3 (ExoPlayer) | 1.8.0 (1.11.x needs compileSdk 36 — stay on 35) |
| Room | 2.8.5 (KSP 2.2.21-2.0.5) |
| TDLib | 1.8.64 via `io.github.tdlib-android:core:0.1.1` + `ktx:0.1.1` (Maven Central, prebuilt AAR, 4 ABIs, 16KB-page ready) |
| DataStore Preferences | 1.3.0 |
| security-crypto (EncryptedSharedPreferences) | 1.1.0 |
| Coil (thumbnails) | 2.7.0 |
| Kotlin coroutines | 1.11.0 |
| Material | 1.13.0 |

Package: `com.nobi.nobistream`. Architecture: MVVM + Repository.
`TelegramGateway` interface seam (so the TDLib implementation can be swapped),
`TdLibGateway` (TdClient init, `SetAuthenticationPhoneNumber`,
`CheckAuthenticationCode`, `CheckAuthenticationPassword`, `SearchPublicChat`,
`GetChatHistory`, `DownloadFile`), `TdlibDataSource` (ExoPlayer DataSource
streaming from TDLib downloads, seek/Range support), Room DB (resume
positions, channels, last group/filter/scroll), `ServiceLocator`.
Include the master `gradle-wrapper.jar` in `gradle/wrapper/` (ACS import hangs
without it). Keep the project ACS-compatible (Android Code Studio on phone).

## 4. Screens (exact strings, English UI)

1. **Login** — Title "Sign in with Telegram", subtitle "Use your own Telegram
   account to watch videos from groups and channels you can already read."
   Phone field (+91), Continue button, then code field, then 2FA password
   field. If API credentials are missing, show a card: "API credentials
   needed — Open Settings and enter the free api_id and api_hash from
   my.telegram.org first."
2. **Home ("My Groups")** — "Continue Watching" horizontal row (thumbnail +
   progress bar + % watched), blue "Add Group / Channel" button, group cards
   (round avatar, name, "N videos"). Empty state: "No groups added yet".
3. **Channel (video list)** — Filter chips, single selection: All (default),
   480p, 720p, 1080p, 4K. Search box ("Search by title or episode"), sort
   "Newest first" / "Oldest first", Refresh. Each video row: 16:9 thumbnail,
   amber "1080p"-style quality badge top-left, duration badge bottom-right
   (e.g. 23:45), title from post text, date + file size, thin blue progress
   bar if partly watched, "Watched" tick if fully watched.
4. **Player** — Black video area (Media3 PlayerView, show buffering when
   playing), title (max 2 lines), Previous / Next buttons, "Autoplay next"
   switch, speed button (0.5x–2x, shows e.g. "1.0x"). Gestures: double-tap
   left/right = 10s back/forward, swipe left edge = brightness, right edge =
   volume. "Resume from 12:34?" dialog with Resume / Restart.
5. **Settings** — Sections: Telegram API credentials (api_id, api_hash
   fields, Save, status line "Credentials are saved on this phone." /
   "No credentials saved yet." / "Credentials are built into this install."),
   Cache (size 500 MB / 1 GB radio, Clear cache), Playback (Autoplay next
   video switch, Default quality filter), Account (Log out of Telegram),
   About text.

## 5. Quality filter logic (from post text)

Parse each post's caption with regex: `1080p` / `1080` / `FHD` → 1080p;
`720p` / `720` / `HD` → 720p; `480p` / `480` → 480p; `2160p` / `4K` / `UHD`
→ 4K. If several match, the HIGHEST wins. Posts with no quality in text are
"Unknown" and show ONLY under All. The Next/Previous order follows the
currently filtered list.

## 6. Design system

- Background `#0B1220`, surface `#131C2E`, surface variant `#1B2740`
- Primary / accent Telegram blue `#229ED9`, primary dark `#1B7FB8`
- Text primary `#FFFFFF`, secondary `#9AA8BC`, disabled `#5B6B80`
- Divider `#24334A`, outline `#2E3D57`, success `#34C77B`, warning `#F5A623`,
  error `#E5484D`, quality badge amber `#FFB020`
- Dark theme only, Material3 components, header below the status bar
  (fitsSystemWindows, one inset mechanism only), no fake buttons — every
  visible control must work.

## 7. Build & delivery

- **PRIVATE GitHub repo only** (`tale92266-hash/NobiStream`), never public.
- GitHub Actions workflow builds a COMPLETE debug APK (no R8, no release
  shrink) and uploads it as an artifact.
- Fixed signing key so every build installs over the previous one: keystore
  `nobistream.p12`, alias `nobistream`. The keystore file and its password
  file are NEVER committed; CI uses secrets `NOBISTREAM_KEYSTORE_B64`,
  `NOBISTREAM_KEYSTORE_PASSWORD`, `NOBISTREAM_KEY_ALIAS`,
  `NOBISTREAM_KEY_PASSWORD`. Without secrets the build still succeeds with
  the default debug key.
- If a build fails, fix it straight away and rebuild — do not stop to ask.
- Never claim a feature is done until it is verified: XML must strict-parse,
  every Kotlin import must resolve, every library must be declared in
  `gradle/libs.versions.toml` + `build.gradle.kts`, no stray `.tmp`/`.bak`
  files under `res/` or `src/`. The phone test is the only verdict.

## 8. What NOT to do

- Do not use Bot API (a bot must be added to the channel — this channel is
  not the user's), do not scrape t.me pages, do not use yt-dlp or
  downloader sites (all verified dead for this use case).
- Do not hardcode api_id/api_hash, do not log them, do not put them in any
  file that gets committed, do not invent fake videos or fake playback.
- Do not rewrite the whole app for a small feature; reuse existing code.
