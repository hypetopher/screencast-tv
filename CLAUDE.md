# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug       # Build APK
./gradlew installDebug        # Build and install on connected device
./gradlew clean               # Clean build artifacts
```

There are no tests, no lint configuration, and no static analysis tooling. Debugging is done via logcat:

```bash
adb logcat -s ScreenCast     # Filter to app log tags
```

NDK 26.3+ is required to build the native FairPlay crypto library (CMake, located in `app/src/main/cpp/playfair/`).

## Architecture

This is an Android TV app (API 21+) that acts as both an **AirPlay receiver** and **DLNA renderer**, allowing iOS/macOS and Android/Windows devices to cast media to a TV.

### Component Overview

```
MainActivity
  ├── starts AirPlayService (port 7000, mDNS _airplay._tcp + _raop._tcp)
  └── starts DlnaService (port 49152, SSDP multicast)

CastEventBus (singleton, thread-safe)
  └── bridges protocol services → UI activities
      Play → PlaybackActivity (ExoPlayer/Leanback)
      StartMirroring → MirrorActivity (MediaCodec + SurfaceView)
      Pause/Resume/Stop/Seek/SetVolume → VideoPlayerManager
```

### Package Structure

- `airplay/` — RTSP/HTTP server, AirPlay pairing, FairPlay crypto, mirror stream decryption, audio receiver
- `airplay/mirror/` — H.264 mirror stream: TCP sink, AES-CTR decryption, MediaCodec feeder
- `dlna/` — UPnP device description, SOAP/AVTransport, SSDP announcements
- `player/` — ExoPlayer lifecycle wrapper (`VideoPlayerManager`), auto-detects HLS/DASH/progressive
- `common/` — `CastEvent` sealed class, `CastEventBus`, `AppPrefs`, `NetworkUtils`

### AirPlay Protocol Flow

1. **Pairing**: Ed25519 long-term keys + X25519 ephemeral ECDH (`AirPlayPairing.kt`). The ECDH shared secret is used to derive AES keys for both mirror and audio streams.
2. **FairPlay**: Two-phase handshake at `/fp-setup`. Phase 1 returns one of 4 hardcoded 16-byte responses (mode-based). Phase 2 extracts key material. The native JNI library (`AirPlayCryptoBridge.kt`) decrypts the FairPlay-wrapped AES key.
3. **Mirror**: iPhone sends RTSP `SETUP` with encrypted AES key (`ekey`) and IV (`eiv`). App opens TCP sink on port 7100. Two threads: producer (reads + decrypts AES-CTR TCP frames → NAL units) and consumer (feeds NAL units to `MediaCodec` hardware H.264 decoder → `SurfaceView`).
4. **Audio**: UDP RTP stream. Packets buffered in a 512-slot ring buffer (indexed by `seqNum % 512`), decrypted with AES-CBC (per-packet), decoded via `MediaCodec` AAC-ELD → `AudioTrack`.
5. **Video casting**: `POST /play` with binary plist containing HLS URL → `CastEvent.Play` → ExoPlayer.

### DLNA Protocol Flow

SSDP multicast → device fetches `/description.xml` (MediaRenderer UPnP) → SOAP `SetAVTransportURI` action → `AVTransportService` → `CastEvent.Play` → ExoPlayer. Position/state sync via periodic `GetPositionInfo`/`GetTransportInfo` SOAP calls.

### Key Implementation Details

- **AES key derivation** (mirror + audio): `SHA-512(fairPlayKey + ecdhSecret).copyOf(16)`
- **Hardware decoder fallback**: Watchdog detects stalled Amlogic decoders (no output after 30 frames / 2s) and shows a Toast. Software fallback is disabled (too slow for 1080p).
- **Port fallback**: AirPlay tries 7000, DLNA tries 49152; both fall back to OS-assigned ports if busy. SSDP advertises the actual bound port.
- **Binary plist**: Parsed with `dd-plist` library. Custom `BinaryPlistWriter.kt` serializes responses.
- **Session state**: Mirroring session keyed by `sessionId` (RTSP header). State fields (`sessionIsMirroring`, `encryptedMirrorAesKey`, `sessionEiv`, `sessionStreamConnectionId`) live in `AirPlayServer`.
- **Threading**: Mirror uses `ArrayBlockingQueue<NALFrame>(capacity=30)` between producer/consumer. Audio uses a fixed-size ring buffer. All UI/ExoPlayer calls post to main thread via `Handler`.
- **Screen-on**: `MirrorActivity` sets `FLAG_KEEP_SCREEN_ON` to prevent standby during mirroring.

### Notable Files

| File | Lines | Notes |
|------|-------|-------|
| `AirPlayServer.kt` | ~1225 | Core RTSP/HTTP handler; all protocol state |
| `VideoPlayerManager.kt` | ~321 | ExoPlayer wrapper; HLS/DASH/progressive auto-detect |
| `AirPlayMirrorStreamProcessor.kt` | — | Producer thread: TCP read, decrypt, parse NAL |
| `AirPlayMirrorRenderer.kt` | — | Consumer thread: MediaCodec + Surface output |
| `AirPlayAudioReceiver.kt` | — | UDP RTP + jitter buffer + AAC decode |
| `AVTransportService.kt` | — | DLNA playback control SOAP actions |
| `troubleshooting.md` | ~420 | Development log; documents all past bugs and fixes |
