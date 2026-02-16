# ScreenCast TV

An Android TV application that turns your TV into a wireless display receiver, supporting both **AirPlay** (iOS/macOS) and **DLNA** (Android/Windows/Linux) casting protocols.

## Features

### AirPlay Receiver
- **Screen Mirroring** - Mirror your iPhone, iPad, or Mac screen to your TV in real-time with hardware H.264 decoding
- **Video Casting** - Stream videos from AirPlay-compatible apps directly to your TV
- **Playback Control** - Play, pause, seek, and stop from your Apple device
- **FairPlay Decryption** - Native C library (playfair) handles FairPlay DRM handshake for encrypted mirror streams
- **Pair-Setup/Pair-Verify** - Ed25519 + X25519 cryptographic pairing compatible with modern iOS/macOS

### DLNA Renderer
- **UPnP Media Renderer** - Appears as a standard DLNA renderer on your local network
- **SSDP Discovery** - Automatically discoverable by DLNA controllers via multicast
- **AVTransport Service** - Full support for SetAVTransportURI, Play, Pause, Stop, Seek, and position/transport queries
- **Wide Format Support** - Accepts MP4, MKV, AVI, HLS, MPEG, and DASH streams

### Video Playback
- Powered by **ExoPlayer (Media3)** with HLS and DASH support
- Android TV optimized UI using the **Leanback** library

## Architecture

```
com.screencast.tv
├── airplay/                    # AirPlay protocol implementation
│   ├── AirPlayServer.kt       # RTSP + HTTP server handling AirPlay requests
│   ├── AirPlayService.kt      # Foreground service for AirPlay
│   ├── AirPlayPairing.kt      # Ed25519/X25519 pair-setup & pair-verify
│   ├── RtspServer.kt          # Custom RTSP server base
│   ├── AirPlayAudioReceiver.kt# UDP audio stream receiver
│   ├── BinaryPlistWriter.kt   # Binary plist serialization
│   ├── PlistParser.kt         # Plist parsing utilities
│   └── mirror/                 # Screen mirroring pipeline
│       ├── AirPlayMirrorStreamProcessor.kt  # TCP mirror stream reader + frame queue
│       ├── AirPlayMirrorDecryptor.kt        # AES-CTR stream decryption
│       ├── AirPlayMirrorRenderer.kt         # MediaCodec H.264 decoder → Surface
│       └── AirPlayCryptoBridge.kt           # JNI bridge to native playfair lib
├── dlna/                       # DLNA/UPnP implementation
│   ├── DlnaServer.kt          # HTTP server for UPnP device description & SOAP
│   ├── DlnaService.kt         # Foreground service for DLNA
│   ├── AVTransportService.kt  # UPnP AVTransport action handler
│   └── SsdpHandler.kt         # SSDP multicast discovery
├── player/                     # Video playback
│   ├── VideoPlayerManager.kt  # ExoPlayer lifecycle management
│   └── PlayerCallback.kt      # Playback state callbacks
└── common/                     # Shared utilities
    ├── CastEvent.kt            # Event types (Play, Pause, Mirror, etc.)
    ├── CastEventBus.kt         # In-process event bus
    └── NetworkUtils.kt         # IP/MAC address helpers
```

### Native Layer (C/C++)

The `app/src/main/cpp/playfair/` directory contains a native library for FairPlay key decryption, built via CMake and accessed through JNI. This handles the cryptographic handshake required for AirPlay screen mirroring.

## Requirements

- Android TV device (or emulator) running **Android 5.0+ (API 21)**
- Devices must be on the **same Wi-Fi network**
- NDK 26.3+ for building native components

## Building

```bash
./gradlew assembleDebug
```

Install on a connected Android TV device:
```bash
./gradlew installDebug
```

## Usage

1. Launch **ScreenCast TV** on your Android TV
2. The app displays your TV's IP address on the main screen
3. **From an Apple device**: Use Screen Mirroring or AirPlay to find "ScreenCast TV"
4. **From a DLNA app**: Select "ScreenCast TV" as the playback device
5. Cast away!

## Dependencies

| Library | Purpose |
|---------|---------|
| [ExoPlayer (Media3)](https://developer.android.com/media/media3) | Video playback with HLS/DASH |
| [Leanback](https://developer.android.com/training/tv/start/layouts) | Android TV UI framework |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | Lightweight HTTP server for DLNA |
| [JmDNS](https://github.com/jmdns/jmdns) | mDNS/Bonjour for AirPlay discovery |
| [EdDSA](https://github.com/str4d/ed25519-java) | Ed25519 signatures for AirPlay pairing |
| [Bouncy Castle](https://www.bouncycastle.org/) | X25519 key exchange |
| [dd-plist](https://github.com/3breadt/dd-plist) | Binary plist parsing for Apple protocols |

## License

This project is for educational and personal use.
