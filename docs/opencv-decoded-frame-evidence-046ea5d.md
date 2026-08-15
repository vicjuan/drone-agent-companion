# OpenCV decoded-frame wiring evidence — `046ea5d`

這份紀錄只證明 production observation session 在 API 34 arm64 emulator 上，能把測試
提供的 canonical NV21 decoded callback 經 vendor `LiveVisionBridge` 與 companion Android
OpenCV 4.9 runtime 產生 segmentation result。它不是 G520／飛機／真相機 evidence，不得
用來升級 capability matrix。

## Frozen candidate

- production commit：`046ea5dae5f621d0762b8220506225f5a4463e39`
- base：`codex/issue-14-opencv-runtime` 的 `9c21024bada490a4afbb108d198bb7e8bcd4ca1b`
- worktree／vendor submodule：clean
- OpenCV Android runtime：官方 `org.opencv:opencv:4.9.0`
- target APK SHA-256：
  `792d717ac82508819998c5dfcb02e91f0f77d1750ab9c56f817ad092432004c0`
- androidTest APK SHA-256：
  `83d3bc5c0c5a7e69f8706ded7b9cbb7fa7a824d3e59ed7defb2a811aa14512e7`
- 兩個 APK 的 `assets/companion-candidate/commit.txt` 都精確為 production commit，
  `worktree-state.txt` 都精確為 `clean`。
- 兩個 APK 都通過 v2 signature verification、4-byte zip alignment，且使用同一 Android
  Debug certificate SHA-256：
  `f3140dcf2f84402d06003fef015deaa208bafa905f7f69839036fa0db508b54f`。
- target APK 的 runtime native entries 只有：
  - `lib/arm64-v8a/libopencv_java4.so`
  - `lib/arm64-v8a/libc++_shared.so`

## Targeted JVM 與編譯邊界

凍結前執行：

```text
./gradlew :host-headless:testMockDebugUnitTest \
  --tests com.durendal.droneagent.companion.host.HeadlessOpenCvObservationSessionTest \
  --tests com.durendal.droneagent.companion.host.AndroidConsoleRuntimeTest \
  --tests com.durendal.droneagent.companion.host.AndroidRuntimeCloserTest \
  --no-daemon --rerun-tasks --max-workers=1
```

結果為 `BUILD SUCCESSFUL`：

- observation session：19/19
- Android runtime composition：7/7
- Android runtime closer：9/9
- 合計 35/35，0 failure／error／skip

另執行：

```text
./gradlew :host-headless:compileMockDebugAndroidTestKotlin \
  :host-headless:verifyOpenCvRuntimeDependencies \
  --no-daemon --max-workers=1
```

結果為 `BUILD SUCCESSFUL`。`scripts/check-companion-boundaries.sh` 亦為 `PASS`；production
observation wiring 沒有 command、admission、actuation、adapter-DJI 或 desktop OpenCV
loader dependency。

## API 34 arm64 emulator runtime

- AVD：`drone_ci_api34_arm64`
- API：34
- ABI：`arm64-v8a`
- fingerprint：
  `google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.050/12077443:userdebug/dev-keys`

對 frozen candidate 只執行一次下列昂貴 lane：

```text
./gradlew :host-headless:connectedMockDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.durendal.droneagent.companion.host.OpenCvAndroidRuntimeInstrumentedTest \
  --no-daemon --rerun-tasks --max-workers=1
```

原始程序退出 0，`BUILD SUCCESSFUL`；Gradle 明列在該 AVD 啟動並完成 2 tests。connected
XML 為 tests=2、failures=0、errors=0、skipped=0：

1. `officialArm64RuntimeSegmentsCanonicalDarkTapeFixture`
2. `productionObservationSessionProcessesRecycledCanonicalNv21Frame`

Instrumentation 結束碼為 `-1`。target／test package 在 lane 結束後均已移除，沒有殘留
PID。

## Decoded-frame runtime result

新測試使用 test-only fake `DecodedFrameStream`，但經過同一 production
`HeadlessOpenCvObservationSession`、vendor `LiveVisionBridge`／drop-to-latest gate／單一 CV
worker，以及真實 Android OpenCV JNI segmenter。測試在 decoder callback 返回後立即將
producer buffer 清零，並用 worker barrier 證明 segmentation 讀取的是 bridge-owned copy，
不是 callback 後失效的 producer buffer。

實際 runtime evidence：

```text
opencv_decoded_frame version=4.9.0
build_sha256=f863f56784c67488a482cd2597fa03ba15ca574cc87bc3962f0a6204312071bd
platform=android-api34-arm64-v8a
format=NV21
analysis_state=IMAGE_SPACE_READY
accepted=true
mask_iou=1.000000
offered=1 polled=1 processed=1 dropped=0
conversion_failures=0 pipeline_failures=0
final=true source_close_count=1
```

測試另確認 callback listener 已解除、source 只成功 close 一次、shutdown discarded frame 為
0、cleanup failure 與 bridge stop timeout 都為 0，且 decoder-receive-to-CV-poll 與 CV
processing summary 各有 1 個 sample。這些 latency 欄位不代表 sensor/camera end-to-end
latency；本 lane 不作 FPS 或 thermal 聲明。

同一 lane 亦再次確認 OpenCV identity 為 4.9.0、native build-information SHA-256 如上、
canonical dark-tape fixture accepted、IoU `1.000000`。該 direct fixture 的 initializer 已由
前一測試 warm up，因此本次初始化時間不是 cold-start benchmark。

## Evidence boundary

這個 slice 的最高狀態是 `RUNTIME_VERIFIED`，且環境嚴格限定為「API 34 arm64 emulator
上的 synthetic decoded-frame wiring」。仍未驗證：

- G520 的實際 `DecodedFrameStream` factory 與 MSDK decoded callback；
- 真實相機 NV21 frame、stride／裁切／解析度變化；
- G520 上的 OpenCV build identity與 recognition result；
- 真機 FPS、latency distribution、drop rate、thermal與長時間穩定性；
- 飛機、相機或任何 actuation 行為。

因此 Issue #14 保持 open，`opencv_on_device_recognition` 與 capability matrix 其餘列維持
`UNKNOWN`。
