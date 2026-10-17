# HomeCast — Design Document

Date: 2026-10-16

## Intent and why

The user wants to cast videos found on web pages (old films, series, live streams) from their Android phone to their Xiaomi Android TV box. Existing ad-supported casting apps do this technically, but show ads on every action (pause/play, switching episodes) and the user doesn't trust them.

HomeCast does the same job as a **personal, ad-free, tracker-free app with no unnecessary permissions**, installed only on the user's own phone. It is not tied to any specific site; it's a general-purpose "find the video on this page and send it to the TV" tool.

Example flow: open a page with an embedded video player, start the player, the app captures the video, the user taps "Play on TV", the film plays on the TV, and the phone becomes the remote.

## Goals

1. Open a page in an in-app browser and capture the video stream from the embedded player (including inside iframes): HLS (`.m3u8`) and direct files (`.mp4`, `.webm`, `.mkv`).
2. Send the captured video to the Xiaomi box via Chromecast (Google Cast protocol), using our own sender implementation — no Google Cast SDK / Play Services. The box is found automatically on the network, or the user types its IP address; the IP is remembered for next time.
3. Add the headers the video server expects (Referer, Origin, User-Agent, Cookie) through a local proxy on the phone, so the TV's requests aren't rejected.
4. Block ads, popups and unwanted redirects in the browser.
5. Remote control from the phone: play/pause, seek (seek bar and ±10 s), volume, stop, subtitles on/off/select.
6. Subtitles:
   - Automatically capture subtitles loaded by the site's player (`.vtt`, `.srt`) and send them to the TV.
   - Let the user add an `.srt` file from the phone.
7. Live streams: HLS live streams (playlists without `#EXT-X-ENDLIST`, continuously refreshed) can be cast. The proxy never caches live playlists; it fetches them fresh from upstream on every request. Sent to Cast with `streamType: "LIVE"`. The remote shows a "LIVE" label instead of the seek bar; no seeking. DRM-protected subscription services are out of scope.
8. Series flow: the user goes back to the browser and opens the next episode; the new video is captured and one tap replaces the video on the TV. Nothing appears in between.

## Non-goals (not in this version)

- DRM-protected subscription services. Not technically possible; won't be attempted.
- Bypassing geo-restrictions (VPN etc.).
- Downloading videos.
- Play Store release, iOS, a separate app on the TV box.
- DLNA/Miracast. Only Cast, since the Xiaomi box has built-in Chromecast.
- Accounts, cloud sync.
- DASH (`.mpd`). If captured, it may be listed and marked "not supported".

## Anti-goals (failures even if it technically works)

- Any ads, analytics, crash-reporting SDK, or anything that sends user data out of the app. Network traffic goes only between the pages the user opens, video servers and the Cast device.
- Unnecessary permissions. Needed: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE (Cast discovery), FOREGROUND_SERVICE (+ `mediaPlayback` type), WAKE_LOCK, POST_NOTIFICATIONS. No storage permission; `.srt` selection uses the Storage Access Framework.
- Playback stopping when the screen turns off or the app goes to the background. Critical, because the proxy runs on the phone.
- The ad blocker breaking the player itself.

## Constraints

- Phones: Samsung Galaxy S24+ and S21+ (Google Play Services present; One UI, so watch for Samsung's aggressive battery optimisation killing the foreground service — prompt the user to exempt the app). minSdk 26, targetSdk/compileSdk 35.
- TV: Xiaomi Mi Box S / TV Box S (4K, Android TV / Google TV) with built-in Chromecast, on the same Wi-Fi network as the phone.
- Delivery: a signed, directly installable **APK** (not AAB). The user will sideload it.
- App UI, code and documentation in English. (Conversation with the user is in Turkish.)
- Dev machine: macOS, `~/Library/Android/sdk` present, system JDK is 23 (may be incompatible with AGP; use JDK 17/21, e.g. Android Studio's JBR). Include the Gradle wrapper in the project.
- Real Cast testing is only possible at the user's home. Development-side verification is limited to unit tests and the emulator.

## Decisions made by the user

- Claude writes the code. The user is an experienced developer but handed the technical decisions over.
- Output is an installable APK.
- Ad-free and trustworthy is the top priority.
- Subtitles: usually come from the player; also wants to add `.srt` files.
- Live streams should be castable.
- App and specs in English.
- Phones: Samsung S24+ / S21+. TV: Xiaomi TV Box S.
- Cast is implemented in-house without Google's Cast SDK, with automatic discovery **and** manual connection by IP address.
- Scope is "what existing ad-supported casting apps do, without the ads": share the video from the page the user is on. Not tied to specific sites.

## Claude's decisions (not confirmed by the user; written down so they're visible)

- Language/UI: Kotlin, Jetpack Compose for screens, Android `WebView` for the browser.
- Cast receiver app: the **Default Media Receiver** (`CC1AD845`), which needs no registration.
- Local proxy: a lightweight HTTP server on the phone, listening on its Wi-Fi IP at a random port (NanoHTTPD or Ktor CIO, builder's choice). Runs inside a **foreground service**, holds a Wi-Fi lock + partial wake lock, and can be stopped from the notification.
- App name: HomeCast. Package name: `io.github.yunuserdemsihhatli.homecast`.

## Public repository and project conventions (user decision: public on GitHub, done properly)

- Licence: **GPL-3.0** (user decision), so nobody can turn the code into a closed, ad-supported app.
- Repo: public, under the user's GitHub account `YunusErdemSihhatli`, created with `gh`. Commits authored by the user.
- Never commit secrets: keystore, passwords, `local.properties` are git-ignored; signing config reads from `local.properties` or environment variables.
- README describes HomeCast as a general-purpose tool to cast videos from web pages; it names no video/streaming sites and states what it does not do (DRM, geo-restriction bypass, downloading). Screenshots added once the UI exists.
- Bundled blocklist must have a licence compatible with the repo's licence (e.g. StevenBlack hosts, MIT); attribution in a `THIRD_PARTY_NOTICES` file.
- CI: GitHub Actions builds and runs unit tests on every push/PR; on a version tag (`v*`) it builds a signed release APK (keystore from GitHub Secrets) and attaches it to a GitHub Release.
- **Design language:** Material Design 3 (Material You, dynamic colour on Android 12+, light and dark themes following the system), Android's official app architecture guidance (UI layer with ViewModels + unidirectional state, data layer, `StateFlow`), edge-to-edge, accessible touch targets and content descriptions, all user-facing strings in `strings.xml`.
- **Code conventions:** official Kotlin coding conventions, enforced with ktlint (or Spotless + ktlint) and Android Lint in CI; Gradle version catalog (`libs.versions.toml`); Conventional Commits for commit messages; `CHANGELOG.md` and semantic versioning.
- Standard repo files: `LICENSE`, `README.md`, `CONTRIBUTING.md` (short), `.editorconfig`, issue templates (bug report / feature request).

## How it works

### 1. Browser screen
- Address bar (URL or search → DuckDuckGo), back/forward/reload.
- WebView settings: JavaScript on, DOM storage on, media playback doesn't require a user gesture, `setSupportMultipleWindows(false)` so no new windows open.
- **Ad blocking:**
  - In `shouldInterceptRequest`, requests matching a domain blocklist bundled with the app (e.g. a compiled hosts list in assets) are answered with an empty response. The list ships with the app and is not updated from the internet (to avoid leaking data and keep things simple).
  - Popups (`window.open`) and `onCreateWindow` are rejected.
  - Main-frame redirects to another domain without a user tap are blocked (`shouldOverrideUrlLoading` + last-touch timestamp). Intent/market schemes (`intent:`, `market:`) are not opened.
  - Video provider iframes are usually on other domains. Subframes are not blocked by domain, only if they match the blocklist.
- **Video capture:**
  - `shouldInterceptRequest` sees requests from all frames. Requests whose URL contains `.m3u8`, `.mp4`, `.webm`, `.mkv` or matches known patterns are recorded as "candidate videos". The record stores the URL, the request headers (especially Referer/Origin; if missing, the requesting frame's URL is used as Referer), the WebView's User-Agent, and cookies for that domain from `CookieManager`.
  - Some players load the playlist without an extension or with a misleading one. For these, a JS script injected into all frames inspects `fetch`/`XMLHttpRequest` responses and reports the URL of any response starting with `#EXTM3U` through a JS bridge. The `src`/`currentSrc` of `<video>` elements is also reported (`blob:` values ignored).
  - Segment requests (`.ts`, `.m4s`, `.aac`) are not candidates.
  - If a master playlist and variant playlists are both captured, the master is preferred (the first playlist in the session containing `#EXT-X-STREAM-INF`).
- When a video is captured, a bar appears at the bottom of the screen: "Video found: [title/domain] — Play on TV". If there are several candidates, the user picks from a list (resolution shown if known).

### 2. Local proxy
- URLs of the form `http://<phone-wifi-ip>:<port>/s/<token>/...`. Each captured video maps to a session (`token`) that stores the original headers. Tokens are random, so others on the network can't guess them.
- **HLS:** the playlist is downloaded and rewritten. All URIs (variant playlists, segments, `#EXT-X-KEY` keys, `#EXT-X-MEDIA` audio/subtitles, `#EXT-X-MAP`) are resolved to absolute URLs and turned into proxy URLs. Segments are streamed through without buffering.
- **Direct files:** the `Range` header is passed through, `206` responses and `Content-Range` are preserved, so seeking works.
- CORS headers for the Cast receiver are added to every response (`Access-Control-Allow-Origin: *` and others as needed). `OPTIONS` requests are answered.
- Upstream errors (403, 404, timeout) are logged and shown in the UI ("The video server rejected the request — reload the page and try again"). Some URLs may be time-limited signed URLs.
- Content-Type is set correctly (`application/vnd.apple.mpegurl`, `video/mp2t`, `video/mp4`, `text/vtt`).

### 3. Cast sender (own implementation, no Play Services)
- **Discovery:** mDNS/DNS-SD for `_googlecast._tcp` via Android's `NsdManager` (holding a `WifiManager.MulticastLock` while scanning). The TXT record gives the friendly name (`fn`) and model (`md`).
- **Manual IP:** a "Connect by IP" field (port defaults to 8009). Saved devices (name + IP + port) are stored locally and tried first on the next launch. If a saved IP fails, fall back to discovery and update the saved IP if the same device (by `id` in the TXT record) is found at a new address.
- **Protocol (CASTV2):** TLS socket to port 8009. The device certificate is self-signed, so certificate validation is skipped for this connection only (scoped to that socket; never a global trust-all). Messages are length-prefixed (4-byte big-endian) protobuf `CastMessage` frames; payloads are JSON strings. The protobuf message is small enough to hand-encode/decode or generate with protobuf-lite.
- Namespaces needed:
  - `urn:x-cast:com.google.cast.tp.connection` — `CONNECT`/`CLOSE` to `receiver-0` and then to the app's `transportId`.
  - `urn:x-cast:com.google.cast.tp.heartbeat` — answer `PING` with `PONG`, send our own `PING` every ~5 s, treat ~15 s of silence as a dropped connection and reconnect.
  - `urn:x-cast:com.google.cast.receiver` — `LAUNCH` `CC1AD845`, `GET_STATUS`, `SET_VOLUME`, `STOP`. Reuse the app if it's already running.
  - `urn:x-cast:com.google.cast.media` — `LOAD` (contentId = proxy URL, contentType, streamType `BUFFERED`/`LIVE`, metadata title, `tracks` + `activeTrackIds` for subtitles, `currentTime`), `PLAY`, `PAUSE`, `SEEK`, `STOP`, `EDIT_TRACKS_INFO`, `GET_STATUS`; consume `MEDIA_STATUS` broadcasts (playerState, currentTime, duration, idleReason) to drive the UI.
- The connection lives in the same foreground service as the proxy, so it survives screen-off and backgrounding.
- If the TV is turned off or another app takes over the receiver, the UI shows "Disconnected" and the proxy session stays alive so the user can resend.

### 4. Remote screen
- Device picker: discovered devices + saved IP devices + "Connect by IP".
- "Play on TV": sends `LOAD` with the proxy URL, the correct contentType, the title (page title) and subtitle tracks.
- Remote screen: play/pause, seek bar, −10/+10 s, volume (the phone's volume keys control the TV volume while the remote screen is in front, and via the media session otherwise), stop, subtitle selection (off / captured / added .srt).
- Notification and lock-screen controls: our own `MediaSession` (androidx media) backed by the Cast connection, with a media-style notification from the foreground service.
- Series flow: "Back to browser" on the remote returns to the page. When a new episode is opened and its video captured, "Play on TV" replaces the current stream. The old proxy session is closed.

### 5. Subtitles
- `.vtt`/`.srt` requests captured in the browser (and HLS `#EXT-X-MEDIA:TYPE=SUBTITLES` tracks) are attached to that video session. Language/label used if known, otherwise the file name is shown.
- The user picks a file via "Add SRT" on the remote screen (SAF, `ACTION_OPEN_DOCUMENT`).
- SRT → WebVTT conversion happens in the app (timestamp comma → dot, `WEBVTT` header, simple HTML tags kept).
- **Character encoding:** Turkish SRTs are often Windows-1254 or ISO-8859-9. If there's no BOM and the bytes aren't valid UTF-8, assume Windows-1254. Output is always UTF-8.
- Subtitles are served from the proxy as `text/vtt` with CORS and given to Cast as tracks (`type: TEXT`, `subtype: SUBTITLES`) in `LOAD`. Switching between already-loaded tracks uses `EDIT_TRACKS_INFO`; adding a new subtitle (e.g. an .srt) re-sends `LOAD` from the current position, since tracks can't be added afterwards.

## Left to the builder

- Project structure and module split, dependency versions, architecture pattern.
- HTTP server library; HTTP client in the proxy (OkHttp suggested).
- Source and size of the blocklist (a reasonably sized hosts list with a suitable licence).
- UI look: simple, dark theme is enough.
- APK signing: create a release keystore, store it outside the project (or excluded from git); tell the user to back up the keystore (updates need the same signature).

## Verification

- Unit tests: HLS playlist rewriting (relative/absolute URIs, KEY, MAP, MEDIA, live playlists), SRT→VTT conversion and encoding detection, Range passthrough, video candidate detection (URL patterns).
- Proxy integration test: a local fake server returns 403 without a Referer; through the proxy it must return 200.
- Cast protocol tests: frame encoding/decoding round-trips, and a fake receiver (local TLS server speaking CASTV2) that checks the CONNECT → LAUNCH → LOAD sequence, heartbeat handling and reconnect.
- `./gradlew assembleRelease` must produce a signed APK and pass `apksigner verify`.
- Real end-to-end testing (page → capture → play on TV, seeking, subtitles, switching episodes, a live stream, 30+ minutes of playback with the screen off) is done by the user. The builder provides a short test checklist for this.


## Test sources (Claude's call)

The user's sites change often, so the design must not depend on any particular site. During development, capture and proxying are checked on the emulator against public HLS/MP4 test streams (VOD and live) and a local test page committed under test fixtures that embeds a player inside a cross-origin iframe, requires a Referer on the media server, and includes fake ad scripts/popups. No third-party video sites are referenced in the repo. The capture logic must also cover common obfuscation: segments disguised as `.png`/`.jpg` (detect by content, not extension) and playlists unpacked by JavaScript (handled by the `#EXTM3U` response hook).
