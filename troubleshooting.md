# AirPlay Troubleshooting Log

## Problem
iPhone discovers `ScreenCast TV`, but connection tears down before media playback/mirroring starts.
Android DLNA casting works.

## Verified Progress (2026-02-13)

### 1. Discovery and /info fixed
- `_airplay._tcp` + `_raop._tcp` advertisement works.
- `/info` binary plist is accepted by iOS.
- `/info` identity now matches runtime values (`deviceid`, `pk`).

### 2. Pairing fixed end-to-end
- `pair-setup` raw 32-byte request is handled.
- `pair-verify` step 1/2/3/4 succeeds.
- Step-4 now returns empty payload (`Content-Length: 0`) as expected.

### 3. FairPlay setup fixed
- `fp-setup` phase 1 (`16 bytes`) implemented with exact RPiPlay mode-based 142-byte replies.
- `fp-setup` phase 2 (`164 bytes`) implemented with exact 32-byte response (`fp_header + req[144..163]`).

### 4. RTSP control-plane advanced
- iPhone now proceeds to:
  - `SETUP` (binary plist)
  - `GET_PARAMETER`
  - `RECORD`
- This proves discovery, pairing, and FairPlay negotiation are no longer the blocker.

### 5. Timing + FairPlay modes validated
- FairPlay mode variants are now accepted (`mode=0/1/2/3` seen in logs).
- `timingPort` handshake works; sender sends UDP timing packets and server receives them.
- `SETUP` contains `eiv`/`ekey` and `updateSessionRequest` fields.

### 6. Build environment fixed on dev machine
- Android SDK license files were missing under `/Users/ax/Library/Android/sdk/licenses`.
- `build-tools;34.0.0`, `platforms;android-34`, and `platform-tools` now install successfully during Gradle build.
- Compile error in `AirPlayServer.kt` fixed (`NSString.content` property access).
- Current install blocker is only ADB device state (`OFFLINE`), not build configuration.

## Current Blocker
Client sends `TEARDOWN` shortly after `RECORD`.

This indicates data-plane/session runtime is still incomplete (control-plane accepted, streaming session not sustained).

## Root Cause (2026-02-13)
Primary remaining issue is `SETUP` stream negotiation mismatch.

Observed pattern from device logs:
- `SETUP` arrives with no top-level `streams` array.
- Request includes `updateSessionRequest`, and the sender tears down quickly after `RECORD`.

Working hypothesis:
- stream declarations can be nested under `updateSessionRequest.streams`;
- if we only read top-level `streams`, server responds without the expected media stream contract and the sender aborts.

## Recent Code Changes
- `app/src/main/java/com/screencast/tv/airplay/RtspServer.kt`
  - RTSP/1.0 framing, CSeq echo, server headers, Date/Session/Audio-Jack-Status.

- `app/src/main/java/com/screencast/tv/airplay/AirPlayPairing.kt`
  - iOS-compatible pair-setup/pair-verify flow.
  - X25519 via BouncyCastle.
  - correct pair-verify response framing.

- `app/src/main/java/com/screencast/tv/airplay/AirPlayServer.kt`
  - `/info`, `/pair-setup`, `/pair-verify`, `/fp-setup` implementations.
  - exact RPiPlay FairPlay constants for `fp-setup`.
  - RTSP method handlers: `OPTIONS`, `SETUP`, `RECORD`, `GET_PARAMETER`, `SET_PARAMETER`, `TEARDOWN`.
  - `SETUP` now parses incoming binary plist (`streams`, `eiv`, `ekey`, `timingPort`, `updateSessionRequest`) using `dd-plist`.
  - stream extraction now checks both:
    - top-level `streams`
    - `updateSessionRequest.streams`
  - removed synthetic stream fallback so responses are strictly request-driven.
  - `SETUP` response is built from requested stream types (mirroring `110`, audio `96`) with dedicated sink ports.
  - temporary TCP sink listeners are started for announced stream ports to avoid immediate socket failure during negotiation.

- `app/build.gradle.kts`
  - added `com.googlecode.plist:dd-plist:1.28` for robust binary plist parsing.

## Current Connection Flow
```
iPhone -> GET /info                ✅
iPhone -> POST /pair-setup         ✅
iPhone -> POST /pair-verify (M1)   ✅
iPhone -> POST /pair-verify (M3)   ✅
iPhone -> POST /fp-setup (16)      ✅
iPhone -> POST /fp-setup (164)     ✅
iPhone -> SETUP                    ✅
iPhone -> GET_PARAMETER            ✅
iPhone -> RECORD                   ✅
iPhone -> TEARDOWN                 ❌ (session not sustained)
```

---

## Updated Status (2026-02-14)

### What Is Fixed Now
The session no longer tears down immediately and the data-plane is live:
- RTSP negotiation reaches the second `SETUP` (with `streams[type=110]`), then `RECORD`.
- Mirror TCP sink accepts the connection (`MirrorSink connected ...`).
- Mirror video packets arrive continuously (`Video packets=240/480/...`).

FairPlay/mirror crypto is now implemented and active:
- NDK + CMake build enabled and JNI library built/packaged.
- `keyMsg(164)` from `/fp-setup` + `ekey(72)` from the first RTSP `SETUP` are used to derive the mirror AES key.
- Mirror stream AES-CTR is derived from `AirPlayStreamKey/AirPlayStreamIV + streamConnectionID` and used for decrypt.
- Logs show the decryptor is armed:
  - `Mirror decryptor configured for streamConnectionID=...`
  - `Mirror decryptor armed for streamConnectionID=...`

The decoder is also receiving valid H.264 early in the stream:
- First frames are queued and decoded (e.g. `Queue frame#0 ... firstNalType=5` then type=1 frames).
- Decoder output format changes to 1920x1080.

### Current Blocker (2026-02-14)
Mirroring still appears "halted"/frozen: TV shows an initial iPhone frame but does not update when the iPhone screen changes.

The key symptom in logs is that *most* incoming mirror payloads are still being dropped at the payload-to-NAL conversion stage:
- Renderer counters show drops dominating received payloads (example run: `Dropped payloads=1440 / received=1493`).
- Only the first few packets parse into NALs; subsequent payloads fail parsing (`Dropped video payload len=...`).

This means:
- crypto is now correct enough to decode some frames,
- but our *payload format parsing is still incomplete* for the majority of mirror payloads.

### What We Tried (2026-02-14)
To address freeze / lack of updates:
- Fixed a codec deadlock risk by draining outputs even when no input buffer is available.
- Added support for AnnexB and multiple NAL-length endianness.
- Honored `avcC` `lengthSizeMinusOne` (NAL length prefix size).
- Added offset scans (skipping 1..256 bytes) for both length-prefixed and AnnexB start codes to recover NALs when a per-packet header exists.
- Added an adaptive decrypt strategy:
  - skips decrypt if payload already looks like H.264
  - probes per-packet CTR vs stream CTR and auto-switches if validation succeeds

Result: still frozen; drops remain very high.

### Working Hypotheses (2026-02-14)
At least one of these is true:
1. Mirror payload type=0 is not a pure sequence of length-prefixed NAL units (may be a container with multiple sections).
2. Some type=0 packets are not video NAL payloads (metadata/heartbeat) but we treat all type=0 as video.
3. There is an additional per-packet header/trailer and/or fragmentation scheme beyond what we currently strip.
4. Decryption mode differs after an I-frame (cipher state misalignment), even though initial frames decode.

### Next Work Items (2026-02-14)
1. Precisely reverse the type=0 payload structure:
   - log a short hexdump for the first N bytes of payloads that are dropped
   - search for embedded AnnexB start codes deeper than 256 bytes
   - identify repeated fields (sequence numbers, flags, sub-packet counts)
2. Update the mirror payload parser to extract the actual H.264 elementary stream from type=0 packets.
3. Only feed `MediaCodec` when an extracted payload validates as H.264; route other sections to debug logs.

---

## Updated Status (2026-02-14, later)

### Mirror Streaming Fixed
- Root cause of 96% payload drops: `findAnnexBStart()` false positive.
  - AVCC NAL lengths 256-511 produce bytes `00 00 01 XX` which match 3-byte AnnexB start code pattern.
  - Fix: removed AnnexB scanning from AVCC conversion; parse length-prefixed NALs directly.
- Mirror stream now decodes and renders continuously on the TV.
- Portrait/landscape aspect ratio handled via MediaCodec crop rect → SurfaceView resize.

---

## Updated Status (2026-02-15)

### AirPlay Video Casting from iPhone — Fixed

Video casting (e.g., playing a video in Safari and AirPlaying it to ScreenCast TV) now works end-to-end.

#### Problem
When selecting ScreenCast TV from the AirPlay menu for video casting, the iPhone would:
1. Open HTTP connections for the event channel.
2. Perform pair-verify → server-info → fp-setup → fp-setup2.
3. Fail at fp-setup2 (FairPlay SAP v1 crypto, not implemented).
4. Fall back to screen mirroring instead of video casting.
5. No `/reverse` or `/play` requests were ever sent.

#### Root Cause
Three issues combined:

1. **`/server-info` returned full features `0x5A7FFFF7,0x1E`** — including bit 2 (VideoFairPlay) and bit 12 (FPSAPv2pt5_AES_GCM), which told iOS to require FairPlay SAP v1 authentication via fp-setup2 before allowing video casting.

2. **`/fp-setup2` returned 200 with invalid crypto** — our handler echoed 20 bytes from the request as a response, but the crypto was wrong. iOS interpreted this as a protocol violation and aborted (returning 421 "Misdirected Request" also didn't help — iOS still gave up).

3. **`/reverse` returned 200 OK** instead of 101 "Switching Protocols" — the wrong status code for a WebSocket-style connection upgrade.

#### Fix

1. **`/server-info` now returns features `0x27F`** (matching UxPlay's approach):
   - Includes: Video, Photo, VolumeControl, HLS, Slideshow, Screen, Audio.
   - Excludes: VideoFairPlay (bit 2), FPSAPv2pt5_AES_GCM (bit 12).
   - iOS no longer requires fp-setup2 for the HTTP event channel.
   - mDNS features (`0x5A7FFFF7,0x1E`) remain unchanged for device discovery and mirroring.

2. **`/fp-setup2` now returns 421 "Misdirected Request"** — safety fallback since iOS no longer hits this endpoint.

3. **`/reverse` now returns 101 "Switching Protocols"** with `Upgrade: PTTH/1.0` and `Connection: Upgrade` headers. The socket is kept alive (soTimeout=0, keepAlive=true) and stored for server→client event push.

4. **Binary plist parsing** for `/play`, `/action`, `/scrub`, `/setProperty` — uses dd-plist `PropertyListParser.parse()` instead of `String(body)` which corrupted binary plists.

#### Working Connection Flow (Video Casting)
```
RTSP connection (mirroring setup):
  iPhone -> GET /info                ✅
  iPhone -> POST /pair-setup         ✅
  iPhone -> POST /pair-verify (×2)   ✅
  iPhone -> POST /fp-setup (16)      ✅  (FairPlay v3 phase 1)
  iPhone -> POST /fp-setup (164)     ✅  (FairPlay v3 phase 2)
  iPhone -> SETUP                    ✅
  iPhone -> GET /info                ✅
  iPhone -> GET_PARAMETER            ✅
  iPhone -> RECORD                   ✅

HTTP connection 1 (event channel):
  iPhone -> POST /pair-verify (×2)   ✅
  iPhone -> GET /server-info         ✅  (features=0x27F, no fp-setup2 needed)
  iPhone -> POST /pair-verify (×2)   ✅
  iPhone -> POST /reverse            ✅  (101 Switching Protocols → PTTH)

HTTP connection 2 (control):
  iPhone -> POST /play               ✅  (binary plist with Content-Location HLS URL)
  iPhone -> PUT /setProperty (×N)    ✅
  iPhone -> POST /rate               ✅
  iPhone -> GET /playback-info       ✅  (polled every ~1 second)
  iPhone -> POST /scrub              ✅
```

#### Key Technical Details
- `/play` body is a binary plist containing:
  - `Content-Location`: HLS master playlist URL (e.g., `https://.../.m3u8`)
  - `Start-Position-Seconds`: playback start offset
  - `uuid`, `clientBundleID`, `clientProcName`, `rate`, `volume`, etc.
- The `CastEvent.Play` is broadcast → `MainActivity.launchPlayback()` → `PlaybackActivity` + ExoPlayer with HLS source.
- iPhone sends `Resume`/`Pause`/`Seek`/`Stop` commands during playback.
- After stopping video, iPhone may fall back to `StartMirroring`.

#### Files Modified
- `AirPlayServer.kt`: `handleServerInfo()`, `handleFpSetup2()`, `handleReverse()`, `handlePlay()`, `handleAction()`, `handleScrub()`, `parsePlistBody()`, `onConnectionUpgraded()`
- `RtspServer.kt`: Added `onConnectionUpgraded()` hook, 101 status handling in `handleClient()`
- `AirPlayService.kt`: No changes (mDNS features unchanged)

## Exact Verification Commands

```bash
ANDROID_HOME=/home/ax/android-sdk ./gradlew :app:installDebug
adb -s 192.168.2.106:5555 shell am force-stop com.screencast.tv
adb -s 192.168.2.106:5555 shell am start -n com.screencast.tv/.MainActivity
adb -s 192.168.2.106:5555 logcat -c
adb -s 192.168.2.106:5555 logcat -s AirPlayServer:D RtspServer:D MainActivity:D AirPlayService:D
```

### Test Screen Mirroring
1. Open Control Center on iPhone.
2. Start Screen Mirroring → select ScreenCast TV.
3. Expect: TV shows iPhone screen in real-time.

### Test Video Casting
1. Open Safari on iPhone, play a video.
2. Tap the AirPlay icon → select ScreenCast TV.
3. Expect: TV shows PlaybackActivity with video playing via ExoPlayer.

---

## AirPlay Mirroring Audio — Fixed (2026-02-16)

### Problem
Screen mirroring video works but audio is silent or distorted.

### Root Causes (multiple issues fixed)

1. **TCP vs UDP**: Original code used TCP audio sinks, but AirPlay mirroring audio uses **RTP over UDP**. TCP sinks never received data.

2. **Missing RTP jitter buffer**: UDP packets arrive out-of-order. AAC-ELD is a stateful low-delay codec — feeding frames out of order corrupts the decoder's internal state, producing severely distorted audio. Fix: 512-slot ring buffer indexed by RTP sequence number, with 8-packet fill before starting playback.

3. **Decryption ordering bug**: `maybeConfigureAudioDecryptor()` was called before `ensureAudioReceiver()`, so `audioReceiver` was null and decryption was never configured. Fix: moved call after stream negotiation loop.

4. **LD-SBR codec crash**: AAC-ELD ASC with LD-SBR (`F8 E8 51 40`) crashes MediaCodec on this Android TV device. Fix: use no-SBR ASC (`F8 E8 50 00`) with IllegalStateException recovery.

### Final Implementation

**`AirPlayAudioReceiver.kt`** (new file):
- UDP sockets for RTP data and control
- RTP header parsing (12-byte base + optional extension)
- AES-128-CBC decryption per packet (NoPadding, only full 16-byte blocks, trailing bytes raw, IV reset per packet)
- **RTP jitter buffer**: 512-slot ring buffer indexed by `seqNum % 512`, buffers 8 packets before starting, drains in sequence order
- Duplicate detection via jitter buffer slot (same seqNum overwrites same slot)
- AAC-ELD decoder via MediaCodec (ASC: `F8 E8 50 00`, 44100Hz stereo 480 SPF)
- AudioTrack for PCM playback (4x minimum buffer)

**`AirPlayServer.kt`** (modified):
- Replaced TCP audio sinks with `AirPlayAudioReceiver`
- Extract `eiv` (16-byte IV) from first RTSP SETUP binary plist
- Audio key derivation: `SHA-512(rawFairPlayKey + ecdhSecret).copyOf(16)` + `eiv`
- `maybeConfigureAudioDecryptor()` called after stream negotiation loop

### Audio Protocol Details
- SETUP: ct=8 (AAC-ELD), sr=44100, spf=480, redundantAudio=2, audioFormat=0x01000000
- RTP: 12-byte header + encrypted AAC-ELD payload
- Encryption: AES-128-CBC, NoPadding, per-packet with session IV (not chained)
- Key = SHA-512(rawFairPlayKey + ecdhSecret)[0:16] (same derivation as video mirror key)
- IV = eiv (16 bytes) from first RTSP SETUP

---

## Video Mirror Buffering — Fixed (2026-02-16)

### Problem
Video quality degraded and occasionally paused during mirroring.

### Root Cause
The entire video pipeline (TCP read → decrypt → decode → render) was synchronous on a single thread. When MediaCodec stalled waiting for input/output buffers, TCP reads would block, causing frame backlog and quality degradation.

### Fix
Added producer-consumer buffering in `AirPlayMirrorStreamProcessor.kt`:
- **TCP reader thread** (producer): reads and decrypts frames, pushes to `ArrayBlockingQueue` (capacity 30)
- **Decoder thread** (consumer): pulls frames in order and feeds to `AirPlayMirrorRenderer`
- If queue fills up, oldest frame is dropped (graceful degradation vs blocking TCP)
- Config packets (SPS/PPS) use blocking put to ensure codec is always configured
- Logs dropped frames and queue depth for monitoring

---

## DLNA Playback Controls Overlay Not Auto-Hiding — Fixed (2026-02-16)

### Problem
When casting video from an Android phone (DLNA), the video file name / title bar stayed visible on screen and never auto-hid. AirPlay casting from iPhone did not have this issue.

### Root Cause
The Leanback `PlaybackTransportControlGlue` overlay (title + transport controls) was not being explicitly hidden after playback started. While Leanback has auto-hide support, it wasn't being triggered reliably for DLNA-initiated playback.

### Fix
In `PlaybackFragment.kt`:
- Enabled `isControlsOverlayAutoHideEnabled = true` on the fragment.
- Added a `Handler.postDelayed()` call in the `onPlaying()` callback to hide the controls overlay 3 seconds after playback begins.
- Controls reappear on remote button press and auto-hide again after the timeout.

---

## Miracast Receiver — Removed (2026-02-16)

### What Was Tried
Attempted to implement Miracast (Wi-Fi Display) sink functionality for Android phone screen mirroring:
- `WifiP2pWfdInfo.setWfdInfo()` to advertise as `DEVICE_TYPE_PRIMARY_SINK`
- RTSP client for WFD M1-M7 session negotiation
- RTP/MPEG2-TS receiver with H.264 + AAC/LPCM decoding

### Why It Was Removed
- `setWfdInfo()` throws `SecurityException` ("Wifi Display Permission denied") — requires `CONFIGURE_WIFI_DISPLAY` system permission that only system-signed apps can hold.
- The system Miracast service (`com.droidlogic.miracast`) on the TV box is a separate app with its own UI — it cannot be leveraged by a third-party app for WFD advertisement.
- Without system-level WFD permission, a regular app cannot make the device discoverable as a Miracast sink.

---

## Issues on Amlogic Android 9 TV (192.168.2.116) — 2026-02-17

### Issue 1: DLNA Not Discoverable from Android Phone

**Symptom**: "ScreenCast TV" does not appear in Android phone casting/DLNA app device lists.

**Root Cause**: `DlnaService: Failed to start DLNA server` — `java.net.BindException: bind failed: EADDRINUSE (Address already in use)` on port 49152. The hardcoded DLNA port conflicts with another service on this device (or a previous app instance scheduled for restart by Android's service recovery). When the HTTP server fails to start, SSDP M-SEARCH responses include a dead URL, so no DLNA controller can reach the device description.

**Fix**:
- Add port fallback: if the preferred port (49152) is unavailable, bind to an OS-assigned port.
- Pass the actual bound port to `SsdpHandler` so SSDP responses advertise the correct URL.

### Issue 2: iPhone Screen Mirroring Shows Black Screen

**Symptom**: iPhone successfully connects for mirroring (MirrorActivity launches, mirror TCP sink accepts connection), but the TV screen stays black.

**Root Cause (confirmed from logcat)**:
1. Mirror sink port 7100 is also `EADDRINUSE` — falls back to random port, which works.
2. Amlogic hardware decoder (`OMX.amlogic.avc.decoder.awesome`) logs: `setPortMode on output to DynamicANWBuffer failed w/ err -2147483648`. This Amlogic-specific OMX decoder does not fully support the DynamicANWBuffer output mode that MediaCodec uses by default on Android 9+. The decoder initializes but fails to produce output frames, resulting in a black screen.

**Fix**:
- Added port fallback in `DlnaService.kt`: `tryStartServer()` first tries port 49152, then falls back to port 0 (OS-assigned). Server startup moved to background thread to avoid ANR.
- Actual bound port passed to `SsdpHandler` so SSDP LOCATION URLs are correct.

### Issue 2: iPhone Screen Mirroring — Hardware Decoder Check

**Symptom**: iPhone successfully connects for mirroring (MirrorActivity launches, mirror TCP sink accepts connection), but the TV screen stays black on devices with broken hardware decoders.

**Root Cause (confirmed from logcat)**:
1. Amlogic hardware decoder (`OMX.amlogic.avc.decoder.awesome`) accepts input frames but produces 0 output frames. After 186 frames over 3 seconds, still zero output.
2. Software decoder (`OMX.google.h264.decoder`) works but is too slow for 1080p real-time on this CPU — causes massive frame drops (300+/minute), unusable quality.

**Fix**:
- Hardware-only decoder with watchdog: if no output after 30 frames / 2 seconds, sets `hwDecoderFailed` flag and stops decoding.
- `errorListener` callback added to `AirPlayMirrorRenderer` — `MirrorActivity` wires it up to show a Toast: "Hardware video decoder not supported on this device".
- Software decoder fallback was tested and removed — too slow for real-time 1080p on low-end CPUs.

### Issue 3: iPhone Video Casting Works

**Confirmed**: AirPlay video casting (HLS playback via ExoPlayer) works correctly on this device — ExoPlayer handles its own decoder selection and avoids the problematic Amlogic OMX component.

---

## Device Standby Prevention — Added (2026-02-17)

### Problem
Android TV goes into standby mode during video casting, interrupting playback/mirroring.

### Fix
Added `FLAG_KEEP_SCREEN_ON` to both casting activities:
- `MirrorActivity` — prevents standby during AirPlay screen mirroring
- `PlaybackActivity` — prevents standby during DLNA/AirPlay video casting

The flag is automatically cleared when the activity finishes, so normal standby behavior resumes after casting ends. No permissions required.

---

## Log Visibility on Android 9 — Note (2026-02-17)

On the Amlogic Android 9 TV, `Log.d()` messages are filtered/invisible in logcat output. All critical diagnostic logs were changed to `Log.i()` or higher in:
- `AirPlayServer.kt` — RTSP SETUP stream negotiation logs
- `AirPlayAudioReceiver.kt` — audio pipeline status logs
