# Sift — File Explorer

A fast, sleek, tab-based file manager for Android with first-class support for
local storage, **root**, and **network shares (SMB & SFTP/SSH)** — built so you
can keep two or more locations open at once and copy/move between them without
the cramped split-screen dance.

## The tab model (the headline feature)
- Each tab is an independent browser at its own location.
- **Swipe right past the right-most tab to open a new tab.** The new-tab page is
  a launcher (internal storage, SD card, root, saved network shares, add new).
- **Swipe left/right between existing tabs** to jump between locations. Copy in
  one, swipe, paste in the next.
- Tap a tab chip to jump to it; tap **×** to close it; tap **+** for a new tab.

## Storage backends
All behind one uniform engine, so every feature works everywhere:
- **Internal storage / SD card** — standard files (asks for *All files access*).
- **Root (`/`)** — whole-device access via `su` (rooted devices only).
- **SMB / CIFS** — SMB2/3 via jcifs-ng. Guest or credentialed.
- **SFTP / SSH** — via JSch. Password or private-key auth.
- **FTP / FTPS** — via Apache Commons Net. Anonymous or credentialed;
  FTPS is explicit TLS (data channel encrypted, accepts self-signed certs).

Saved connections are stored in **AES-256 EncryptedSharedPreferences** — your
credentials are never written to disk in plaintext.

## Features
- List **and** grid views, per-tab.
- **Image & video thumbnails**, and **folder previews** — a 2×2 collage of the
  images inside a folder, rendered on its tile.
- Built-in **image viewer** (pinch-zoom / double-tap / pan) and **text viewer/editor**
  — edit and save on **any** backend (writes back to remote shares), find, word-wrap
  toggle, copy-all, and **"Open as text"** to force any file into the editor.
- Multi-select: **copy, move (cut), delete, share, rename, compress to ZIP,
  extract ZIP, copy path, properties** (with recursive folder-size calc).
- Cross-backend operations with progress + cancel (e.g. SFTP → SMB copy streams
  straight through; same-backend moves are server-side/instant).
- Sort by name / size / date / type, folders-first, show/hide hidden files.
- Breadcrumb path bar, in-folder search, pull-to-refresh.
- Material 3 design, light/dark, edge-to-edge.

## Build
Requirements: JDK 17, Android SDK (platform 35, build-tools 34.0.0).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

### Signing
Release builds are signed with credentials read from `keystore.properties` at the
project root. **This file and the keystore are not committed** — you supply your own.

1. Generate a keystore (one-time):
   ```bash
   keytool -genkeypair -v -keystore sift-release.jks -alias sift \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Create `keystore.properties` next to `build.gradle`:
   ```properties
   storeFile=sift-release.jks
   storePassword=yourStorePassword
   keyAlias=sift
   keyPassword=yourKeyPassword
   ```

Both are gitignored. If `keystore.properties` is absent the release build is left
unsigned rather than failing, so the project still builds without it.

- `minSdk` 26, `targetSdk` 35, package `com.sift.explorer`.
- Libraries: jcifs-ng (SMB), JSch/mwiede (SFTP), Commons Net (FTP/FTPS),
  Glide (thumbnails), AndroidX Security (encrypted credential store),
  Material 3, ViewPager2.
