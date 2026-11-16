# Onyx Performance Monitor

A minimal native Android app project for rooted Android devices. It is configured for the user's onyx / SM8735 device, but uses standard Linux procfs, power-supply sysfs and SurfaceFlinger diagnostics.

## Features
- Manual START / STOP; no fixed duration or automatic test timeout.
- Requests root through `su` (KernelSU Next compatible).
- FPS estimate from SurfaceFlinger frame-present timestamps for the foreground app's SurfaceView layer.
- Battery current, voltage, percentage and battery-side power estimate.
- CPU0–CPU7 system-wide utilization from `/proc/stat` deltas.
- Optional session averages toggle.
- Session min/max FPS, battery percentage change and CSV export.

## Build APK
1. Open this folder in Android Studio (recent version with Android Gradle Plugin 8.7.3 support).
2. Allow Gradle sync and install Android SDK Platform 35 if prompted.
3. Choose Build > Build Bundle(s) / APK(s) > Build APK(s).
4. APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Install the debug APK, open it, and grant root access when KernelSU Next prompts.

## Measurement notes
- CPU utilization is system-wide, not process-specific.
- FPS is a recent SurfaceFlinger timestamp-window estimate. Some games or ROMs may not expose a compatible SurfaceView layer; those samples are shown as unavailable, not 0 FPS.
- Power is calculated as `-(current_now × voltage_now) / 10^12`, assuming current_now is in µA and voltage_now is in µV. It is a battery-side estimate, not total SoC rail power. Unplug the phone for discharge measurements.
- Sampling interval is approximately one second. Shell/dumpsys overhead means it is not an exact real-time instrument.
