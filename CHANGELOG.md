# Changelog

All notable changes to Sift are documented here. Versions are tagged `vX.Y`.

## [1.11] — 2026-08-03
### Fixed
- Opening audio or video from a network share or the root backend no longer hands
  the player unrelated files as its "folder". Remote files are downloaded to the
  cache before being handed over, and the folder passed as `ClipData` in 1.10 was
  read from that cache directory — so every file opened earlier looked like a
  sibling. Each remote file is now staged into its own directory, keyed by its
  filesystem and path, and the sibling scan skips cache-staged files outright.
  Such files now play as a single item, which is the correct answer for a folder
  whose other files have not been downloaded.
- As a consequence, two shares holding a file of the same name no longer overwrite
  each other's cached copy.

## [1.10] — 2026-08-03
### Added
- Opening audio or video now hands the player the rest of the folder as intent
  `ClipData`, so its next/previous can move through the folder. The read grant
  on the intent covers every item, so this works for folders the system media
  index can't see — anything under a `.nomedia`, or files copied in since the
  last scan — and needs no media permission in the receiving app. Attached only
  for folders with 2–500 items of the same kind.

## [1.9] — 2026-08-02
### Added
- Opening indexed local media now hands the external player a
  `content://media/external/<type>/media/<id>` (MediaStore) URI instead of a
  private FileProvider URI, so players can resolve the file by id and reliably
  queue the whole containing folder. Non-indexed, remote, and non-media files
  fall back to the FileProvider URI as before.
- `.ts` / `.m2ts` / `.mts` now report `video/mp2t` so video players' `video/*`
  intent filters recognize MPEG-TS files.

## [1.8] — 2026-08-02
### Fixed
- Audio/video opened from Sift no longer keeps playing in the background until
  Sift is force-closed. External apps are now launched in their own task
  (`FLAG_ACTIVITY_NEW_TASK`), so the player gets its own Recents entry and is
  closed independently of Sift.

## [1.7] — 2026-07-25
### Fixed
- Word wrap in the text editor (it never actually wrapped).

## [1.6] — 2026-07-24
### Changed
- Text editor overhaul: save on any backend (writes back to remote shares),
  find, word-wrap toggle, and "Open as text" to force any file into the editor.

## [1.5] — 2026-07-17
### Changed
- Dark-mode top bar is now black; fixed the DrawerLayout status-bar scrim.

## [1.4] — 2026-07-17
### Added
- Disk usage view with a storage bar.
### Changed
- Darkened the dark-mode top bar.

## [1.3] — 2026-07-17
### Docs
- Documented that contributors must supply their own signing keystore.

## [1.2] — 2026-07-17
### Added
- FTP and FTPS support (Apache Commons Net); FTPS is explicit TLS.

## [1.1] — 2026-07-16
### Added
- Persist last-used sort across tabs and app launches.
### Fixed
- Opened location now focuses correctly when launched from the home page.

## [1.0]
- Initial release: tabbed file explorer with local, root, SMB and SFTP backends,
  image/text viewers, multi-select file operations, and cross-backend copy/move.
