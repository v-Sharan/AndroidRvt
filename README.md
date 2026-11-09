# AndroidRvt

Android viewer for live RTSP video using GStreamer, with control and tracking over UDP.

## Building

APKs are built by GitHub Actions, so no local Android tooling is needed. Every push to
`main` runs `.github/workflows/build.yml`; download the APK from the run's **Artifacts**
section.

To build locally instead, you need JDK 17, the Android SDK, NDK `29.0.14206865`, and the
GStreamer Android universal binaries. Set `GSTREAMER_ROOT_ANDROID` to the unpacked
GStreamer folder and run `gradle assembleDebug` with Gradle 8.11.1.

## Adding the real UDP protocol

The app currently speaks a temporary text protocol from `link/PlaceholderCodec.kt`. To
replace it, implement `ProtocolCodec` and pass it to `UdpLink` in `MainViewModel.kt`.
Nothing else needs to change.

`tools/udp_simulator.py` acts as a camera for the placeholder protocol.

## Layout

- `app/src/main/jni/video_engine.c` - GStreamer pipeline, frame watchdog, reconnection, recording
- `app/src/main/java/com/androidrvt/viewer/video/` - Kotlin bridge to the engine, video coordinates
- `app/src/main/java/com/androidrvt/viewer/link/` - UDP link and protocol codec
- `app/src/main/java/com/androidrvt/viewer/net/` - Wi-Fi binding
- `app/src/main/java/com/androidrvt/viewer/ui/` - Compose screens

Using `test` as the RTSP URL shows a built-in test picture.
