# 撰寫順序（Weekend Web Control MVP 優先）

## 用途與前提

本文件給的是**程式碼撰寫順序**，前提是手邊沒有 G520、G520 尚未刷成 Android、
也沒有接 RC-N3 與 Mini 4 Pro。

本週末的產品目標不是先完成產品化，而是先交付董事長可以立刻看見、操作的最小
垂直切片：

> 在 Mac 上啟動 `console-runner`，瀏覽器可看見即時 mock telemetry，並可透過
> 完整 admission → authority → audit 路徑操作 takeoff、landing、RTH、上升／下降、
> 前進／後退、左旋／右旋。takeoff、landing 與連續控制終止於上游 `adapter-mock`；
> 因上游沒有 RTH action port，RTH 明確終止於 companion-owned observable simulation。

排序原則是先做出這條可見的 end-to-end slice，再補 Android host、產品化認證、影像
與維運。帳號、密碼、TLS、credential lifecycle、多使用者管理都不屬於週末 MVP。
安全機制不因排程而省略：命令不得繞過 admission，virtual-stick 必須有單一 operator
control lease、TTL、dead-man timeout 與斷線 neutral。

硬體相關的 issue（#6、#8、#9 與 #7／#11 的硬體部分）等 G520 到手後立即進入
commissioning，見文末「週末無法驗證、下週才可開始的工作」。

## 架構前提修正：gateway 不翻轉為 server

初版 `docs/architecture.md` 曾把本案描述為「把 gateway 從 client 翻轉成
server」。該描述已修正，理由如下，且直接影響 S2 起的模組切法：

`drone-agent-android` 的 `gateway` 模組實作的是**與 drone-platform 後端凍結
的 Phase-0 agent protocol**。它的欄位對齊後端手寫的 Java model，並以
`contracts/agent-protocol/CANONICAL.sha256` 與
`scripts/check-contract-fixture-sync.sh` 鎖住 golden fixtures。那是一份跨
repo 的外部契約，不是可以為了 UI 需求自由增刪的內部格式。

正確切法：

| 對象 | 協定 | 方向 |
| --- | --- | --- |
| G520 agent ↔ drone-platform | 既有凍結 Phase-0 agent protocol（`gateway`，不動） | agent 連出去（client） |
| G520 agent ↔ 操作者瀏覽器 | **新的 console 協定**（本 repo 自有） | agent 在機上聽（server） |

兩者共用的是 `core` 的狀態模型與 `gateway.admission` 的**政策物件**
（`CommandAdmissionPolicy`、`CommandInbox`），不是 wire format。命令授權
邏輯只有一份，wire 格式有兩份，各自服務各自的對象。

## 環境準備

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
```

本機預設 JDK 是 25，會讓 Gradle 8.7 失敗，每個新 shell 都要先設定。

## 撰寫順序

### S0　建立可建置的 workspace（issue #1）

以 git submodule 引入 `drone-agent-android`，Gradle composite build
（`includeBuild`）引用其模組，不複製原始碼、不 fork。建立本 repo 的模組骨架：

```text
console-protocol   Kotlin/JVM   console wire model 與 codec
capability-matrix  Kotlin/JVM   G520 evidence data、validator 與 Markdown renderer
commissioning-network Kotlin/JVM 完整網路 inventory 的 fail-closed selection policy
console-server     Kotlin/JVM   WebSocket server、靜態資源、政策接線
console-runner     Kotlin/JVM   JDK-only 開發用 runner（對照 gateway-runner）
web-console        TypeScript   瀏覽器 SPA
vision-opencv-core     Kotlin/JVM  OpenCV backend + injected native initializer
vision-opencv-desktop  Kotlin/JVM  Mac fixture/replay runtime（S10 實作）
vision-opencv-android  Android     G520 native packaging／initializer（S10 實作）
host-headless      Android app  無頭 host（S8 才開始填內容）
```

`console-server` 必須維持 **pure JVM，不得引用任何 Android API**。理由與
`gateway` 選 OkHttp 相同：同一份實作要能在 `console-runner`（Mac）與
`host-headless`（Android）兩邊跑。這也順帶避開 DJI jar 在 JVM 單元測試中
觸發 VerifyError 的老問題 — 廠商型別一律留在 adapter 那側。

**完成判準**：乾淨 clone（`--recurse-submodules`）後 `./gradlew build` 綠；
submodule 內既有的 guard 腳本（`check-core-vendor-neutral.sh` 等）納入 CI 並通過。

### S1　把 capability matrix 做成可被程式讀取的資料（issue #12）

不要只寫一份 markdown 表格。本 stack（Mini 4 Pro + RC-N3 + G520）的每一列
硬體能力初始一律 `UNKNOWN`。這份資料是 operational readiness 與硬體結論的唯一
證據來源；它不能因 mock 成功而升級，也不能被拿來宣稱下週真機一定可用。

`core/capability/CapabilityMatrix.kt` 已有五個 telemetry／streaming 型別可投影，但它
不足以表示 G520 boot、Ethernet、USB、MSDK、致動與 OpenCV commissioning，因此由本
repo 的 pure-JVM `capability-matrix` 模組持有完整 open-ID schema。canonical source 是
`config/capability-matrix/g520-stack.json`；`console-server` 與 `web-console` 讀它呈現能力
證據。一般 operational profile 依 matrix 決定可開放範圍；明確的 hardware
commissioning session 可在人工控制的測試條件下逐項產生第一手證據，但不能預先把
`UNKNOWN` 改成可用。markdown 文件與這份資料由同一來源產生或以測試鎖住一致性。

**完成判準**：matrix 資料檔與 markdown 一致性有測試保護；所有硬體列為
`UNKNOWN`；有一個「證據條目」範例（日期、commit、硬體序號組合、操作者、
原始 log 位置）。

### S2　定義 console 協定（issue #3 的前半）

新的 wire model，寫法對照 `gateway/protocol/Messages.kt` 與
`gateway/codec/ProtocolCodec.kt`（kotlinx.serialization，明確序列化 null 而非省略）。

訊息面至少涵蓋：連線建立、runtime／adapter 狀態、telemetry 推送、capability
matrix 快照、control lease、命令下行、命令 ack／result、連線健康度。命令 model
從第一版即包含 takeoff、landing、RTH 與 virtual-stick；連續控制 frame 必須有
sequence 與 TTL。

週末 localhost profile 不要求帳號認證；協定仍保留可擴充的 handshake，#5 再加入
credential 與 TLS，不得為此更動凍結的 Phase-0 agent protocol。

**跨語言一致性**：server 是 Kotlin、SPA 是 TypeScript，兩端型別必須鎖住。
建議放一組 golden fixtures，Kotlin 測試與 TypeScript 測試讀同一批 JSON 檔案，
任一端改壞就會紅。這是 `gateway` 那套 fixture 紀律的內部版本，成本低很多
（兩端都在本 repo，不需要 vendoring 與 sha 鎖）。

**完成判準**：codec round-trip 單元測試通過；fixtures 被兩端測試共用。

**2026-08-16 實作狀態**：v1.0 仍固定 18 種 message type（client 7、
server 11），原 canonical fixtures、manifest 與 SHA-256 不變。`client_hello` 固定用
v1.0 bootstrap envelope 廣告 `[1.1, 1.0]`，server 以每 session 最高共同版本
回覆；v1.1 delta 新增一種 server-only `commissioning_authority_state`，不新增
browser mutation。Kotlin 與 TypeScript 共讀 v1.1 delta fixtures/manifest；codec 繼續採
方向性 decode/encode、exact-key validation、RFC 8259 數值語意、64 KiB frame 與
16 層 nesting 上限。client payload 不含 authority assertion；這些是 console 內部契約，
未更動上游凍結 agent protocol。

### S3　實作 console server（issue #3 的後半）

WebSocket + 靜態資源服務，pure JVM。

**目前選型**：JVM runner 使用 Ktor CIO（WebSocket + static content）。Android 上
沒有 `com.sun.net.httpserver`，OkHttp 也只有 client；Ktor 目前只在 Mac/JVM 候選
版驗證。必須在 #2 實測 Android engine 相容性與 APK 體積後，才能把這個
選型延伸到 `host-headless`。

接線：訂閱 `core` 的 `TelemetrySource`、讀 S1 的 matrix、命令一律送進
`CommandAdmissionPolicy` 與既有 actuation arbitration。不得存在任何繞過 admission
的 HTTP 端點。server 同時負責單一 operator control lease、virtual-stick TTL 與
dead-man neutral。

**完成判準**：單元測試涵蓋連線生命週期、多 client 爭用 control lease、refused
dispatch 必有回覆，以及輸入停止／瀏覽器斷線／lease 失效時強制 neutral。

**2026-08-16 實作狀態**：已建立 transport-independent `ConsoleServerCore`、Ktor
transport、single-operator lease、discrete/control admission、dead-man/watchdog、neutral
barrier 與 JSONL audit。server-owned readiness gate 在 admission、commit 與 executor 前都重驗
adapter/connection/lock/profile；狀態失效會撤銷 lease 並 neutral。Origin gate、
clickjacking headers、static symlink boundary、handshake ordering 與 global lease truth 均有測試。
hardware commissioning authority 已從 runtime observer truth 分離：Core 持有 exact
operator session/generation/monotonic TTL/immutable allowlist，v1.1 只投影個人化唯讀
狀態，且 DJI 公開 runtime lock 全程維持 `LOCKED`。這是 JVM 契約，不是
production DJI composition 或 G520 硬體證據；Ktor Android 仍未驗證。

### S4　console-runner：讓整套系統在 Mac 上跑起來

對照 `gateway-runner/src/main/kotlin/.../Main.kt` 的做法，把
`console-server` + `adapter-mock` 組成 JDK-only 可執行程式。

這一步是整個週末的槓桿點：完成後，後面每一步都能在 Mac 上開瀏覽器直接看到
結果，不需要任何硬體、不需要 Android emulator。

**完成判準**：`./gradlew :console-runner:run` 只綁 `127.0.0.1`；瀏覽器可建立
WebSocket 連線、收到 mock telemetry，且 mock adapter 能回傳命令 ack／result。

**2026-08-15 實作狀態**：Mac composition root 已接上 `adapter-mock`、runtime readiness、
telemetry、audit 與 Ktor，預設 `127.0.0.1:8080`；可只改 loopback port 以避開本機占用，
bind host 仍不可改。上游 mock 沒有 RTH action port，
因此 runner 以 companion-owned observable simulation seam 顯示 `RETURNING_HOME`；此路徑
只是 demo 可觀察性，不是 DJI/G520 RTH 證據。

### S5　web console SPA v1：可見、可控制的 MVP（issues #4、#10）

完全對著 S4 開發。呈現電池、定位、飛行狀態、gimbal／camera、連線健康度，並提供：

- takeoff、landing、RTH 的明確操作與結果回饋；
- 上升／下降、前進／後退、左旋／右旋的 press-and-hold 或 joystick 控制；
- 放開、pointer cancel、視窗失焦與頁面離開時立即送 neutral；
- adapter（Mock／DJI）、aircraft connection、control lease 與 actuation lock 狀態。

UI 不需要固定的「MOCK DEMO」橫幅，但不可隱藏實際 adapter 與連線狀態。capability
matrix 標 `UNKNOWN` 的能力仍需如實呈現，且 mock runtime 不得顯示成硬體已確認。

開發期 SPA 由 `console-runner` 直接指向前端建置輸出目錄；正式打包時作為
resources 內嵌，單一產物即可服務。

**Weekend MVP 完成判準**：mock 下瀏覽器可看到即時更新；上述命令全部經完整
admission → authority → audit 路徑到達明定的 mock terminal 並收到結果（RTH terminal
是 companion simulation，不冒充 upstream adapter）；第二個 browser client 不能取得
同一 control lease；斷線有明確 UI 狀態並自動重連；server 可觀察到 neutral。

**2026-08-15 實作狀態**：SPA 已顯示 runtime/health/telemetry/capability/lease，
並提供需確認的 takeoff/landing/RTH 與六向 press-and-hold。release、cancel、
pointer-capture 失敗、鍵盤焦點轉移、window blur、visibility/pagehide 都匯入
同一個 idempotent neutral 路徑；protocol error 會鎖住當前 session 並重連。UI 狀態
保留 `UNKNOWN` capability，也不會把 targeted lease denial 誤當全域 lease truth。

**2026-08-16 browser acceptance**：真實 Chromium browser 已在 1280px 寬 viewport 連上
loopback runner 驗證。TAKEOFF／LANDING／RTH 使用頁內 modal 明確確認；取消不會 dispatch，
確認後 UI 會顯示 ack/result。六向控制全數收到 `APPLIED`，每次釋放都收到 neutral
`SUCCEEDED`；第二個 browser session 在 lease 已持有時維持 blocked，釋放後同步恢復可取得。
17 列 target-stack capability 仍全為 `UNKNOWN`。candidate-bound 截圖、去識別 audit 統計與
hash 見 [`docs/evidence/browser-control-runtime-2ed458f.md`](evidence/browser-control-runtime-2ed458f.md)。

### S6　點對點 Ethernet commissioning profile（issue #6）

G520 刷成 Android 後，Windows 筆電透過 USB 轉 RJ-45 與網路線直連 G520；這是
初期唯一支援的網路拓樸，不再評估 Wi-Fi AP 或 USB RNDIS。兩端使用固定 IP，server
只綁 G520 的該 Ethernet 介面。Windows 必須關閉 Internet Connection Sharing 與
network bridge。

**完成判準**：G520 開機後無人工操作即可使用固定 IP；Windows 瀏覽器可取得 SPA、
維持 WebSocket 與取得 control lease；確認 server 沒有監聽其他介面；結果寫入
evidence。這只能在真板上達到 `HARDWARE_VERIFIED`。

**2026-08-16 準備狀態**：已新增 pure-JVM fail-closed network inventory policy，以及
Windows 唯讀 collector／跨平台 validator。候選 `10.52.0.0/30` 與 adapter binding 仍是
`UNVERIFIED_TEMPLATE`；Android Ethernet collector、指定 Network 的 listener、network-loss
neutral、Windows 真機與 G520 evidence 都尚未完成。詳見
[`g520-point-to-point-ethernet-commissioning.md`](g520-point-to-point-ethernet-commissioning.md)。

### S7　認證與傳輸安全產品化（issue #5，週末 MVP 後）

點對點 commissioning profile 暫不建立帳號、密碼或 TLS。#5 涵蓋後續 token／
credential 佈建與輪替、區網 TLS、自簽憑證信任流程、session timeout 與 audit。

這項工作是把系統擴大到共享、無線或可路由網路前的 admission gate；不得把點對點
commissioning 例外變成所有介面的 production 預設。

**完成判準**：未認證連線只能取得健康檢查端點，有測試證明；認證失敗寫 audit；
共享網路 profile 不提供 cleartext command surface。

**2026-08-16 準備狀態**：開始以 pure-JVM exposure policy 與 authentication admission
kernel 封住 generic LAN cleartext／pre-auth core session；shared/routable production profile
仍明確 disabled，point-to-point commissioning 也只能產生 non-runnable candidate，不能在缺少
live Android Network pin／freshness gate 時開 listener。TLS connector、自簽憑證信任、credential provisioning／rotation、active
session expiry／revocation、authenticated HTTP bootstrap 與目標環境 runtime evidence 尚未
完成，因此不得把此準備切片當成 issue #5 驗收。詳見
[`production-web-console-security.md`](production-web-console-security.md)。

### S8　無頭 Android host，在 emulator 上（issue #2）

到這裡才需要 Android。`RECEIVE_BOOT_COMPLETED` receiver 啟動 foreground
service，service 承載 agent 生命週期與 S3 的 console server；不依賴任何
Activity 存活；一般 process death 由 Android `START_STICKY` service recreation 恢復，
重啟事件寫 evidence log。這不是 app 內另一個會與 agent 同時死亡的假 watchdog。

用 mock flavor 在 emulator 驗證。本 repo 已把 Mac runner 的 mock execution／snapshot
抽成 `console-adapter-mock`，底層沿用 vendor `adapter-mock`；Android common source 不得依賴
mock。詳細 frozen
candidate、instrumentation、boot 與 force-stop 步驟見
[`headless-emulator-runbook.md`](headless-emulator-runbook.md)。

Android 平台不允許一般 app 在 `am force-stop` 後自行解除 stopped state；因此驗收拆成：

- 一般 `:agent` process death：`START_STICKY` 重建、新 PID、console 恢復與 durable restart
  evidence；
- `force-stop`：預期持續停止、無 PID、無 health，直到外部明確 commissioning start；
- app-owned safe stop：先 neutral／close／fsync，再停止 foreground service。

API 34 emulator 的 fresh-install 第一次啟用由同 candidate 的專用、無 service side effect
instrumentation 明確完成；它只解除 stopped state，真正 server 啟動仍必須來自後續系統
boot broadcast。這不是 G520 production commissioning：Activity-free、non-exported APK 在
fresh sideload 後沒有 operator 可直接啟動的 production 元件，#8 必須另行選定 system
image／Device Owner／privileged installer／受控 supervisor 的一次性啟用方式並取得真機證據。

**注意**：emulator 通過**不代表** G520 通過。開機自啟行為、廠商省電策略、
foreground service 是否被回收，都必須在真板上重驗。這一列在 matrix 裡維持
`UNKNOWN`。

**完成判準**：第一次明確啟用後，API 34 emulator 重開機不需 Activity，service 達到
foreground/running；內嵌 SPA、`/healthz`、WS hello/runtime/telemetry 與 mock command
admission 可用；一般 process death 後恢復並留下新 PID／restart evidence；safe stop 完成
flush；force-stop 負向測試維持停止。所有結果最高只到 emulator `RUNTIME_VERIFIED`，matrix
仍全為 `UNKNOWN`。

**2026-08-16 實作狀態**：已建立 minSdk 26／targetSdk 34 的 Activity-free mock flavor、
non-exported boot receiver 與 `connectedDevice` foreground service；service 在 `:agent` process
承載 S3 server + mock agent，SPA 由固定 allowlist/SHA assets 原子安裝，lifecycle/restart
evidence 以 app-private、bounded、fsync journal 保存。

API 34 arm64 emulator 已達 `RUNTIME_VERIFIED`：candidate `c3fec9c` 的 connected lane 3/3
通過，直接驗證 Android POSIX audit、一般 `:agent` process death 後新 PID／durable restart，
以及 foreground Ktor／SPA／strict WebSocket／lease／takeoff／safe-stop 路徑。後續
candidate `876b9f12977c572a23ca8d3636a1405727a485c6` 只改 androidTest provisioning、驗證腳本與
文件；production 的 17 個 DEX 與 `c3fec9c` 逐一 byte-identical。它的唯一 lifecycle lane
亦通過：fresh install `stopped=true, notLaunched=true`、無副作用 instrumentation 明確啟用、
真實 reboot 後 15 秒內 health、boot ID 變更、全新 PID／durable boot evidence、strict
forwarded WebSocket mock takeoff，最後 force-stop 維持停止。target／test APK SHA-256 分別為
`9e121c3eb5a9e9b8f23781473754760aa7cdffc0680c852499d5a14046db2376` 與
`b405c658372e0553c8fc406996da980767a5f42ee93fde90af81428a74f6037a`；本機原始證據在
`host-headless/build/emulator-evidence/20260815T164604Z-84563/`。這些結果只證明 mock +
API 34 emulator，G520 commissioning、OEM policy 與 aircraft-side process-loss failsafe 仍未
驗證，matrix 全列維持 `UNKNOWN`。

### S9　影像鏈路的無硬體部分（issue #7 的一半）

在 Mac 上跑 MediaMTX，用 ffmpeg 推一個合成訊源到 RTMP ingest（單一路徑段
等於 streamId，不使用 `/live/<key>`），web console 內嵌 WHEP player 播放。

這樣可以在沒有飛機的情況下，把 player、ingest 路徑慣例、SPA 內嵌播放全部
寫完並驗證。MediaMTX 最終放機上還是地面站的決策，等 G520 到手後量測再定。

**完成判準**：瀏覽器可播放合成訊源；端到端延遲有量測紀錄（Mac 環境數字，
不得當作 G520 的數字）。

### S10　G520 on-device OpenCV 視覺辨識（issue #14，Weekend MVP 後）

重用 `drone-agent-android:vision` 的 vendor-neutral frame/result contracts，但不能把它
現有的 desktop `compileOnly` OpenCV 誤當成 Android runtime integration。本 repo 的
`vision-opencv-core` 依賴 `LuminanceFrame` → `Segmenter` → `SegmentationResult` 這條
最小 vendor-neutral 接縫；`vision-opencv-desktop` 提供 Mac native runtime；
`vision-opencv-android` 負責 Android native packaging／initialization，並由 host 從
`drone-observation` 接收 decoded frames，執行第一個經人類確認的 recognition／tracking
pipeline。RTMP／WHEP 是人眼觀看鏈，不能取代 decoded-frame CV input。

第一個 target 固定為亮色地面上的深色膠帶 segmentation。core 與平台 runtime 共用
同一個 native self-test fixture；Mac pin `org.openpnp:opencv:4.9.0-0`，Android pin
官方 `org.opencv:opencv:4.9.0` 並只包 arm64-v8a。fixture 成功只能驗證 native loader、
OpenCV operation 與 segmentation contract，不能取代 `DecodedFrameStream` 或 G520
commissioning evidence。

2026-08-16 的 frozen candidate `498cf519…e62dd` 已在 Mac 與 API 34 arm64 emulator
實際執行同一 fixture；APK native／dependency boundary 亦通過。完整 hash、runtime
identity、latency 與限制見
[`opencv-runtime-evidence-498cf51.md`](opencv-runtime-evidence-498cf51.md)。此證據沒有
decoded frame 或 G520 硬體，故 #14 仍未完成且 matrix 維持 `UNKNOWN`。

先在 Mac fixture/replay 驗證演算法，再於 emulator 驗證 packaging boundary；真正
完成仍需在 G520 Android 上識別 native library 與 runtime build information，並以
實際 frame → OpenCV operation → recognition result／evidence 證明執行路徑。

**完成判準**：OpenCV 型別不洩漏到 `core`、console protocol 或 admission；Mac
fixture 測試可區分有無 OpenCV 實作；Android 產物包含正確 ABI native library；G520
runtime 產生 OpenCV build identity 與至少一筆實際辨識結果。Mac/emulator 證據不得
升級 G520 capability matrix。

## 依賴關係

```text
S0 ─┬─> S1 ────────────────┐
    └─> S2 ──> S3 ──> S4 ─┴─> S5  ← Weekend Web Control MVP
                  ├────────────> S8 ──> S6  ← G520 到手後 commissioning
                  └────────────> S9
S6 ──> S7  ← 擴大到共享／可路由網路前
S8 + decoded-frame source ──> S10  ← OpenCV on-device vision
```

S1 與 S2 可並行。週末先完成 S0–S5；S8 只要 S3 完成即可開始，但若資源有限，排在
可見的 S5 之後。S6 需要 G520 Android 真板。S7 是產品化 gate，不阻塞 localhost
mock demo 或受控點對點 commissioning。

S10 是明確產品需求，但不插隊阻塞 Weekend Web Control MVP；其 G520 runtime 驗證依賴
S8 host 與 #9 的真機 decoded-frame path。

## 週末無法驗證、下週才可開始的工作

以下即使寫了程式碼也**無法驗證**，本階段不排入，避免產生「寫完了」的錯覺：

| Issue | 為何做不到 |
| --- | --- |
| #8 無頭 USB 權限與 RC-N3 attach | 需要真板與 RC-N3；權限策略選擇依賴 G520 Android build 的實際行為 |
| #9 MSDK 在 G520 註冊啟用 | 需要真板、RC-N3、飛機；本案關鍵風險，不可用 emulator 或 mock 替代 |
| #6 點對點 Ethernet | 拓樸已決定，但固定 IP、指定介面 bind 與開機可達性仍需真板驗證 |
| #7 影像鏈的真機訊源部分 | 需要飛機提供真實 RTMP 訊源 |
| #11 部署與維運的真板部分 | 安裝形態依賴 #8 的權限策略；安全關機 SOP 需真板實測 |

## 證據紀律

週末所有產出都是 **mock 與 emulator 證據**。依既有紀律，這些都不能升級任何
硬體能力狀態：mock 行為、可編譯、單元測試通過、issue 關閉，一律不構成硬體
證據。`docs/capability-matrix.md` 的硬體列在 G520 到手並產出第一手證據之前，
全部維持 `UNKNOWN`。

週末結束時的正確說法是「完整 Web 控制面在 mock 下可運作」，不是「系統可以操作
Mini 4 Pro」。下週硬體 commissioning 的目標是透過 Web 完成起飛、降落、RTH、
上升／下降、前進／後退與左旋／右旋；能否達成仍取決於 #8、#9 的第一手結果。

最後更新：2026-08-14。
