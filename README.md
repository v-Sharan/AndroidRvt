# AndroidRvt

Android viewer for live RTSP video using GStreamer, with control and tracking over UDP.

## Building

APKs are built by GitHub Actions, so no local Android tooling is needed. Every push to
`main` runs `.github/workflows/build.yml`; download the APK from the run's **Artifacts**
section.

To build locally instead, you need JDK 17, the Android SDK, NDK `29.0.14206865`, and the
GStreamer Android universal binaries. Set `GSTREAMER_ROOT_ANDROID` to the unpacked
GStreamer folder and run `gradle assembleDebug` with Gradle 8.11.1.

## Layout

- `app/src/main/jni/` - GStreamer pipeline and JNI bridge (C)
- `app/src/main/java/com/androidrvt/viewer/` - Kotlin app and UI
