# OpenCV runtime evidence — `498cf51`

這份紀錄只證明 companion OpenCV foundation slice 在 Mac 與 API 34 arm64 emulator
實際執行。它不是 G520／飛機 evidence，不得用來升級 capability matrix。

## Frozen candidate

- commit：`498cf519264cc5725a4df3deac56181a3b6e62dd`
- worktree／vendor submodule：clean
- OpenCV：4.9.0
  - desktop：`org.openpnp:opencv:4.9.0-0`
  - Android：官方 `org.opencv:opencv:4.9.0`
- Android AAR SHA-256：
  `932519cec0b771759fd2910f9ba33beea06408d752f7e6b51b01b5ddac9d8dab`

OpenCV 4.9 是第一版可直接從 Maven Central 取得官方 Android AAR 的 release；
Android loader 使用 `OpenCVLoader.initLocal()`。來源：
[OpenCV releases](https://github.com/opencv/opencv/releases)、
[OpenCV change log](https://github.com/opencv/opencv/wiki/OpenCV-Change-Logs-v2.2%E2%80%90v4.10)。

## Mac native runtime

命令：

```text
./gradlew :vision-opencv-core:test :vision-opencv-desktop:test --no-daemon --rerun-tasks
```

結果：

- BUILD SUCCESSFUL；core 7/7、desktop 3/3，0 failure／error／skip。
- platform：`desktop-Mac OS X-aarch64`
- version：`4.9.0`
- native build-information SHA-256：
  `e041a760da6f3b9bf0ff3341b9ed2880070558c8558963339982dd140d448a07`
- canonical dark-tape fixture：accepted；IoU `1.0`（門檻 `0.9`）
- warm segmentation latency：`125458 ns`

## Android artifact

- target APK SHA-256：
  `9f267bfc60ec6134a30f1784fb952514b6e9d1b7df0c271d6c93a5f41d059d03`
- androidTest APK SHA-256：
  `b84a08a36a3f3664c4533547e2c0f6428f0f95027fd560239bf2f2e22f60ff2b`
- 兩個 APK 的 embedded commit 都是 `498cf519…e62dd`，worktree state 都是
  `clean`；安裝後拉回的兩個 APK hash 與 build artifact 逐 byte 相同。
- package：`com.durendal.droneagent.companion.host.mock`
- min／target SDK：26／34；APK v2 signature 與 zip alignment 通過。
- OpenCV native allowlist：只有 `lib/arm64-v8a/libopencv_java4.so`；另有官方
  runtime 需要的同 ABI `libc++_shared.so`。沒有其他 OpenCV ABI 或 desktop OpenCV
  native／loader dependency。

## API 34 arm64 emulator runtime

- AVD：`drone_ci_api34_arm64`
- ABI：`arm64-v8a`
- fingerprint：
  `google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.050/12077443:userdebug/dev-keys`
- instrumentation：精確執行
  `OpenCvAndroidRuntimeInstrumentedTest`，`OK (1 test)`，code `-1`
- version：`4.9.0`
- native build-information SHA-256：
  `f863f56784c67488a482cd2597fa03ba15ca574cc87bc3962f0a6204312071bd`
- initialization latency：`22559209 ns`
- warm segmentation latency：`4767875 ns`
- canonical dark-tape fixture：accepted；IoU `1.000000`；tape pixels `144`

instrumentation 同時從已安裝 target APK 驗證 OpenCV native exact allowlist、desktop
loader class absence與 public API boundary。完成後 target／test package 已從 emulator
移除。

## Evidence boundary

最高狀態是 `RUNTIME_VERIFIED`，環境限定為 Mac 與 API 34 arm64 emulator。仍未驗證：

- G520 的 `DecodedFrameStream`／NV21 → `LuminanceFrame` → OpenCV production wiring；
- G520 上的 OpenCV build identity、真實 decoded frame 與 recognition result；
- 真機 FPS、latency distribution、thermal 與失敗行為；
- 飛機或相機硬體。

因此 Issue #14 保持 open，`opencv_on_device_recognition` 保持 `UNKNOWN`。
