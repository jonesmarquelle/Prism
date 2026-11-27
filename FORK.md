# This fork: YouTube Music Desktop output

This is [Prism](https://github.com/Yigder/Prism) with one addition: **YouTube Music Desktop (YTMD) on a PC is a Prism
Connect output**, next to speakers, TVs and Prism TVs in the player's output picker. Pick a PC, and the music plays
there; the phone stays the remote.

It keeps Prism's package id (`com.prism.music`), so it installs over stock Prism, and apps that look for Prism by its
package (clod-droid) keep working. Build with `-PnoYtmd` for stock Prism.

## How it works

- **Finding the PC:** YTMD 2.1.0+ announces its companion server with mDNS (`_ytmdesktop._tcp`), so PCs on the same
  Wi-Fi show up by themselves. For one mDNS doesn't reach (another subnet, a VPN), use **Add a PC by its address**.
- **Pairing (once per PC):** Prism asks YTMD for a code and shows it; YTMD shows the same code and asks whether to allow
  Prism. Allow it there within 30 seconds. The token is kept on the phone, so it doesn't ask again.
- **Playing:** as with a Prism TV, the phone's own player carries on, muted, so the queue, autoplay, lyrics, the
  notification and play counts all work as ever. Each song is sent to YTMD by its video id (`changeVideo`): YTMD plays
  it from YouTube Music itself. Play, pause, seeks and the volume go across; YTMD's realtime feed (Socket.IO) moves the
  phone's playhead with the PC, so lyrics stay in time. Playing, pausing or picking another song on the PC comes back
  to the phone. Songs YouTube Music doesn't have (files on the phone, podcasts by RSS) play on the phone meanwhile.

## Also in this fork: "Needs a look", with a search

Bring your history's **Needs a look** shows the three songs YouTube Music's search put first, which can all be remixes
or remasters while the song itself is further down. In this fork:

- Each card has **Search for it…**: it searches YouTube Music's songs with the song's name (edit it as you like), and
  any result can be played or picked with **This one**.
- The three suggestions put the track's own version first (the same title and version words, then a remaster of it,
  then the rest), so the plain song isn't crowded out.
- **Passed over** (under Matching, on Bring your history) lists the songs you said **None of these** to, which upstream
  never shows again, so you can pick or search for them later.

The code is in `ui/screens/ImportReviewSearch.kt` and `res/values/strings_import_review.xml`, with small hooks in
`ui/screens/BringHistoryScreen.kt`, `data/imports/HistoryImports.kt`, `data/imports/TrackMatcher.kt`,
`data/db/Imports.kt`, `ui/Nav.kt` and `MainActivity.kt`.

## On the PC

In YTMD's settings, **Integrations**: turn on **Companion server**, and **Enable companion authorization** while
pairing. The PC's firewall has to let the phone reach port 9863.

## Where the code is

| What | Where |
|---|---|
| Companion API client, state | `app/src/main/java/com/prism/music/ytmd/YtmdApi.kt` |
| Realtime feed (Socket.IO over a WebSocket, by hand) | `ytmd/YtmdRealtime.kt` |
| mDNS discovery, PCs by address, tokens | `ytmd/YtmdDesktops.kt` |
| Playing along (phone leads what, PC leads where) | `ytmd/YtmdCast.kt` |
| Output picker group, pairing code, add by address | `ui/player/YtmdOutputs.kt` |
| Words (English only) | `res/values/strings_ytmd.xml` |
| Tests | `app/src/test/java/com/prism/music/ytmd/YtmdTest.kt` |

Upstream files are touched only where the output hooks in: `connect/Cast.kt` (discovery, connect, the player's
events), `connect/Upnp.kt` (the `YTMD` kind), `ui/player/PlayerControls.kt` (two lines), `data/Updates.kt` (where
updates come from) and `app/build.gradle.kts` (the `YTMD`, `UPDATE_REPO` and `forkRevision` build settings).

## Updates: upstream's, with the fork kept

Prism's in-app update banner asks **this fork's** releases, not upstream's (`BuildConfig.UPDATE_REPO`, in
`data/Updates.kt`). `.github/workflows/fork-release.yml` makes them:

1. Every 6 hours it checks upstream's latest release (say `v1.8.7.7`). If the fork hasn't got it, it merges that tag
   into `main`. Because the fork's code is in its own files, this is normally a clean merge.
2. If `main` has anything the fork's last release lacks (upstream's release, or a fork change pushed to `main`), it
   builds a signed release APK and publishes it as **`v1.8.7.7.1`** with `Prism.apk` attached, along with upstream's
   release notes. A later fork-only fix becomes `.2`, and so on.
3. The phone's updater sees `1.8.7.7.1` as newer than what's installed, offers it, downloads it and installs it in
   place, the same as a normal Prism update.

If upstream's release doesn't merge cleanly, nothing is released. Instead a pull request named "Merge upstream Prism
vX" is opened with the conflicts. Resolve and merge it, and the next run releases. You can start a run any time from
**Actions → Fork release → Run workflow**.

### One-time setup

- **A signing key.** Every release has to be signed with the same key, or Android refuses to install one over another.
  Make a keystore once (`keytool -genkeypair -keystore prism-fork.jks -alias prism -keyalg RSA -keysize 4096 -validity 20000`)
  and keep it somewhere safe. Then add these repository secrets under **Settings → Secrets and variables → Actions**:
  `SIGNING_KEYSTORE_B64` (`base64 -w0 prism-fork.jks`), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
  `SIGNING_KEY_PASSWORD`.
- **Switching from official Prism.** Official Prism is signed with its developer's key, so the fork can't be installed
  over it. Back up first (Settings → Backup & about → Backup), uninstall Prism, install the fork's `Prism.apk` from this repo's
  releases, and restore the backup. After that, updates come from the fork in place.
- If upstream ever adds files under `.github/workflows/`, the workflow can't push that merge (`GITHUB_TOKEN` may not
  change workflows). Merge that one release by hand.

By hand, the merge is:

```bash
git remote add upstream https://github.com/Yigder/Prism.git
git fetch upstream --tags
git merge v1.8.7.7
```
