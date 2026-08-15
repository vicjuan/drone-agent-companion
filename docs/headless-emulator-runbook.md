# Headless Android emulator 驗證手冊

本手冊只產生 API 34 emulator 的 runtime evidence；它不能證明 G520、DJI MSDK、
RC-N3、Mini 4 Pro、Ethernet、Doze/OEM policy 或 aircraft-side failsafe。

## 平台邊界

- `START_STICKY` 只用於一般 service process death 後由 Android 嘗試重建，沒有固定秒數
  SLA。
- `am force-stop` 會把 package 設成 stopped state。app 自己的 receiver、alarm、service
  或 watchdog 都不能解除；只有使用者或外部管理者的明確啟動能恢復。因此 force-stop
  必須是「維持停止」的負向安全測試。
- foreground service、console server 與 dead-man scheduler 目前在同一 `:agent`
  process。process death 後，app 不可能替已死亡的自己送 neutral；DJI commissioning 前
  必須以第一手硬體 evidence 證明 adapter／aircraft-side input loss failsafe。
- Ktor/static/audit 路徑使用 `java.nio.file`，Android host 的最低版本暫定 API 26。

## Frozen candidate 順序

所有 production code、必要測試與 targeted checks 完成後，先把 candidate commit，並確認
tracked、staged、untracked 都為空。昂貴 lane 只接受這個乾淨 commit；`git diff` 不會包含
untracked 或 staged 內容，不能拿來當 candidate 指紋：

```bash
test -z "$(git status --porcelain=v1)"
git rev-parse HEAD
git show --format=fuller --no-ext-diff --binary HEAD | shasum -a 256
```

同一 candidate 的下列昂貴 lane 各最多執行一次：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/Users/vic/Library/Android/sdk

./gradlew :host-headless:testMockDebugUnitTest
./gradlew :host-headless:assembleMockDebug :host-headless:assembleMockDebugAndroidTest
./gradlew :host-headless:lintMockDebug
./gradlew :host-headless:connectedMockDebugAndroidTest
```

Instrumentation 會驗證 foreground service 內的實際 Ktor `/healthz`、SPA、WebSocket
hello/runtime/telemetry、全 `UNKNOWN` capability、lease、takeoff admission/result、safe stop
及同 UID `:agent` process death 後的新 PID／sticky restart evidence。

完成 connected lane 後，再驗 boot 與 force-stop 負向路徑。Android Test Orchestrator／UTP
會在 connected lane 結束時卸載 target；fresh sideload 又會把 Activity-free package 設為
`stopped=true, notLaunched=true`，因此不能假設 connected 的明確啟用狀態仍存在。lifecycle
腳本會先 fresh uninstall/install target 與同一 candidate 的 androidTest helper，再只執行一個
不啟動 service 的 provisioning instrumentation，合法解除 stopped state後才進入真正 reboot：

```bash
scripts/verify-headless-emulator-lifecycle.sh \
  host-headless/build/outputs/apk/mock/debug/host-headless-mock-debug.apk \
  host-headless/build/outputs/apk/androidTest/mock/debug/host-headless-mock-debug-androidTest.apk \
  emulator-5554
```

這個步驟會清除 emulator 上該 mock package 的既有 app data，並在結束時移除 helper APK。
provisioning 前腳本必須看到 fresh target 為 `stopped=true, notLaunched=true`；instrumentation
只載入 target Application，核對 target／helper 內嵌的 candidate 身分，不得呼叫 receiver、
service 或建立 lifecycle journal。helper 移除後，腳本再要求 target 為
`stopped=false, notLaunched=false`、main／`:agent` process 均不存在、health 不可達且沒有
lifecycle current／previous。第一筆 lifecycle evidence 與第一個 `:agent` PID因此只能由後續
真實 reboot 的系統 boot broadcast 產生。

腳本固定建立 `host 127.0.0.1:18080 → device 127.0.0.1:8080` forward；不能任意換 host
port，因為 Android mock server 的 exact WebSocket Origin gate 也固定為
`http://127.0.0.1:18080`。這個 host port 刻意避開本機其他開發服務常用的 8080。腳本在
接觸裝置前會以 `/tmp` 下的 host-global serial／port locks 拒絕另一個並行 lane，再拒絕
dirty／uncommitted worktree，並
記錄 frozen candidate commit；同時 fail-closed 檢查 `adb`、`curl`、`rg`、`python3`、
`shasum`、`node` 與 `unzip` 是否可執行。第一次等待 boot 完成後，腳本要求
`ro.kernel.qemu=1`、`ro.build.version.sdk=34`，且 fingerprint／ABI 不可為空；不符合時不會
產生成功結論。AVD 名稱依序取自 `ro.boot.qemu.avd_name`、
`ro.kernel.qemu.avd_name`、device shell 的 `AVD_NAME`；皆無資料時 fail closed，不會用
serial 猜測。

腳本會把 bounded、單行化的裝置身分寫入 `device-identity.txt`，核對 installed target／helper
APK SHA，並要求兩個 built／installed APK 內的 `assets/companion-candidate/commit.txt` 都等於
當前 clean HEAD，且 `worktree-state.txt` 都記錄 identity generator 當時觀察到 `clean`，避免
把一般的舊或 dirty APK runtime 誤標到該 commit。重開後的 runtime smoke 前後也會重新
pull／hash installed target APK，鎖定同一個 `:agent` PID，並要求 Android kernel `boot_id`
確實改變。這是 frozen lane 的受信任 operator 程序 guard；
完整 assembly 結尾仍會重新檢查 clean HEAD，驗證期間禁止 `-x` 排除 identity task 或並行
修改工作樹，但不把 marker 誇稱為抵抗惡意並行篡改的原子 attestation。之後重開 emulator、
確認 boot receiver 後 PID、health 與 SPA，並從 Mac 端透過同一 adb
forward 執行 strict WebSocket Upgrade（含 exact Origin）、hello、mock runtime、lease 與
takeoff admission/result。WebSocket 驗證摘要會寫入 `ws-forwarded.json`，其 SHA-256 也會
收進 summary。接著腳本要求 fresh provisioning 的 lifecycle baseline 為空，並以 reboot 前的
device epoch 作嚴格水位；`adb reboot` 必須實際觀察到 disconnect 與不同的 kernel `boot_id`，
且只接受水位之後的新 boot 與相符 PID runtime evidence，因此 PID reuse 不會讓舊資料假通過。
emulator 的明確時間界線是 `sys.boot_completed=1` 後 45 秒內出現 health；summary 會同時記錄
實測秒數與 deadline。G520 的 cold-power-on SLA 仍須真板另行訂定與驗證。
最後 force-stop 並確認十秒內沒有 PID 或 health
自行恢復。腳本結束時 package 刻意留在 stopped state；後續工作需透過 instrumentation、
Device Owner 或其他明確 commissioning 動作重新啟用。

## 證據解讀

- emulator 通過的最高狀態是 `RUNTIME_VERIFIED`（API 34 emulator）。
- `summary.txt` 固定記錄 clean candidate commit、API 34 emulator 環境、fingerprint、AVD、
  ABI、APK／forwarded WebSocket evidence SHA-256 與 `highest_claim=RUNTIME_VERIFIED`；
  `device-identity.txt` 保留原始 getprop 欄位名稱，兩者都只寫 bounded、sanitized
  single-line 值。
- lifecycle script 的 summary scope 只涵蓋 `boot_console_force_stop`，並明列它本身沒有
  證明 connected instrumentation；process-death／safe-stop 證據必須另以同一 frozen
  candidate 的 `connectedMockDebugAndroidTest` 原始退出結果支撐。
- provisioning instrumentation 只是 emulator 的外部明確啟用工具，不是 G520 production
  commissioning 機制。production APK 沒有 Activity，boot receiver／service 也都是
  `exported=false`；G520 fresh install 必須由 #8 另行選定並以第一手證據驗證 system image、
  Device Owner、privileged installer 或其他受控 supervisor 的一次性啟用流程。
- `config/capability-matrix/g520-stack.json` 的 `headless_boot_service` 與所有硬體列維持
  `UNKNOWN`。
- `adapter=mock`、companion-owned RTH simulation、Ktor Android runtime 都不是 G520 或
  aircraft actuation 證據。
- 真機到手後仍需冷開機、使用者解鎖模式、OEM 電源政策、長時間存活、網路、MSDK、
  process-loss neutral/failsafe 逐項重驗。
