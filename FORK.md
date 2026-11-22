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
events), `connect/Upnp.kt` (the `YTMD` kind), `ui/player/PlayerControls.kt` (two lines) and `app/build.gradle.kts`
(the `YTMD` build flag).

## Keeping up with upstream

`.github/workflows/upstream-sync.yml` runs daily: it merges Yigder/Prism's `main` into this fork's `main` on the
`upstream-sync` branch and opens a pull request (with the conflicts to resolve when there are any). It needs
**Settings → Actions → General → Allow GitHub Actions to create and approve pull requests** turned on. By hand:

```bash
git remote add upstream https://github.com/Yigder/Prism.git
git fetch upstream
git merge upstream/main
```
