# 撰寫順序（無硬體階段）

## 用途與前提

本文件給的是**程式碼撰寫順序**，前提是手邊沒有 G520、G520 尚未刷成 Android、
也沒有接 RC-N3 與 Mini 4 Pro。

因此排序原則只有一條：

> 先寫「硬體到位後才需要被插進來的那些接縫」，並且每一步都要能在開發用的
> Mac 上以 `adapter-mock` 獨立驗證完成與否。

硬體相關的 issue（#6、#8、#9 與 #7／#11 的硬體部分）刻意不排進來，見文末
「本階段做不到的事」。

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
console-server     Kotlin/JVM   WebSocket server、靜態資源、政策接線
console-runner     Kotlin/JVM   JDK-only 開發用 runner（對照 gateway-runner）
web-console        TypeScript   瀏覽器 SPA
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
硬體能力初始一律 `UNKNOWN`，而且這份資料要成為 S7 致動 fail-closed 的**唯一
判斷依據**，否則「哪些命令可以開放」就會散落成各處手寫的 if。

`core/capability/CapabilityMatrix.kt` 已有型別可重用。做法：本 repo 提供
G520 stack 的 matrix 資料來源，`console-server` 讀它決定命令面開放範圍，
`web-console` 讀它決定 UI 呈現。markdown 文件與這份資料由同一來源產生或以
測試鎖住一致性。

**完成判準**：matrix 資料檔與 markdown 一致性有測試保護；所有硬體列為
`UNKNOWN`；有一個「證據條目」範例（日期、commit、硬體序號組合、操作者、
原始 log 位置）。

### S2　定義 console 協定（issue #3 的前半）

新的 wire model，寫法對照 `gateway/protocol/Messages.kt` 與
`gateway/codec/ProtocolCodec.kt`（kotlinx.serialization，明確序列化 null 而非省略）。

訊息面至少涵蓋：連線建立與認證交握、telemetry 推送、capability matrix 快照、
命令下行、命令 ack／result、連線健康度。

**跨語言一致性**：server 是 Kotlin、SPA 是 TypeScript，兩端型別必須鎖住。
建議放一組 golden fixtures，Kotlin 測試與 TypeScript 測試讀同一批 JSON 檔案，
任一端改壞就會紅。這是 `gateway` 那套 fixture 紀律的內部版本，成本低很多
（兩端都在本 repo，不需要 vendoring 與 sha 鎖）。

**完成判準**：codec round-trip 單元測試通過；fixtures 被兩端測試共用。

### S3　實作 console server（issue #3 的後半）

WebSocket + 靜態資源服務，pure JVM。

**待決技術選型**：Android 上沒有 `com.sun.net.httpserver`，OkHttp 也只有
client。候選是 Ktor（CIO engine，含 WebSocket 與 static content，可在 Android
執行）或 NanoHTTPD。建議 Ktor，但**必須實測其 Android 相容性與 APK 體積後
才算數**，選定理由寫進 PR。

接線：訂閱 `core` 的 `TelemetrySource`、讀 S1 的 matrix、命令一律送進
`CommandAdmissionPolicy`。不得存在任何繞過 admission 的 HTTP 端點。

**完成判準**：單元測試涵蓋連線生命週期與多 client 併發；未認證 client 依既有
refusal 語意被拒絕**且必有回覆**（沿用 drone-agent-android #246 的行為，
「拒絕」不等於「沉默」）。

### S4　console-runner：讓整套系統在 Mac 上跑起來

對照 `gateway-runner/src/main/kotlin/.../Main.kt` 的做法，把
`console-server` + `adapter-mock` 組成 JDK-only 可執行程式。

這一步是整個週末的槓桿點：完成後，後面每一步都能在 Mac 上開瀏覽器直接看到
結果，不需要任何硬體、不需要 Android emulator。

**完成判準**：`./gradlew :console-runner:run`，瀏覽器連 localhost 可建立
WebSocket 連線並收到 mock telemetry。

### S5　web console SPA v1，唯讀（issue #4）

完全對著 S4 開發。呈現電池、定位、飛行狀態、gimbal／camera、連線健康度。

**紀律**：capability matrix 標 `UNKNOWN` 的能力，UI 必須明示 UNKNOWN，不得
渲染成看起來可用的樣子。這是後面真的接上飛機時，避免操作者誤判的第一道防線。

開發期 SPA 由 `console-runner` 直接指向前端建置輸出目錄；正式打包時作為
resources 內嵌，單一產物即可服務。

**完成判準**：mock 下瀏覽器可看到即時更新；斷線有明確 UI 狀態並自動重連；
此版本不存在任何送出命令的 UI 路徑。

### S6　認證與傳輸安全（issue #5）

排在命令面之前。順序反過來的話，開發期會先出現一條無認證的命令路徑，之後
很難確定它有沒有殘留。

涵蓋：token／credential 的佈建與儲存（runtime input，不進 URL、Gradle、
manifest、BuildConfig，沿用既有紀律）、區網 TLS 與自簽憑證方案、cleartext
開發模式的明確 opt-in 邊界、session 逾時與多操作者併發策略。

**完成判準**：未認證連線只能取得健康檢查端點，有測試證明；認證失敗寫 audit。

### S7　命令路徑，致動 fail-closed（issue #10）

只開放非致動類命令（串流啟停、模式查詢、log 標記）走完整條
admission → authority → audit 路徑。

致動類（takeoff、virtual stick、RTH、landing）的介面可以先寫，但預設關閉，
且**必須在 server 與 UI 兩層都拒絕** — 只把按鈕藏起來不算數。開放條件是 S1
的 matrix 中該列有本 stack 的第一手證據，而本階段不可能有。

**完成判準**：mock 下非致動命令端到端成功；未授權 session 的命令被拒且有
audit；致動命令在 server 層被拒的行為有測試鎖住。

### S8　無頭 Android host，在 emulator 上（issue #2）

到這裡才需要 Android。`RECEIVE_BOOT_COMPLETED` receiver 啟動 foreground
service，service 承載 agent 生命週期與 S3 的 console server；不依賴任何
Activity 存活；程序層 watchdog 與重啟事件寫 evidence log。

用 mock flavor 在 emulator 驗證。`drone-agent-android` 已有
`scripts/run-emulator-ci.command` 與 emulator 執行流程可參考。

**注意**：emulator 通過**不代表** G520 通過。開機自啟行為、廠商省電策略、
foreground service 是否被回收，都必須在真板上重驗。這一列在 matrix 裡維持
`UNKNOWN`。

**完成判準**：emulator 冷開機後無人工介入，service 達到 running 並開始寫 log；
kill 後自動恢復且留下重啟紀錄。

### S9　影像鏈路的無硬體部分（issue #7 的一半）

在 Mac 上跑 MediaMTX，用 ffmpeg 推一個合成訊源到 RTMP ingest（單一路徑段
等於 streamId，不使用 `/live/<key>`），web console 內嵌 WHEP player 播放。

這樣可以在沒有飛機的情況下，把 player、ingest 路徑慣例、SPA 內嵌播放全部
寫完並驗證。MediaMTX 最終放機上還是地面站的決策，等 G520 到手後量測再定。

**完成判準**：瀏覽器可播放合成訊源；端到端延遲有量測紀錄（Mac 環境數字，
不得當作 G520 的數字）。

## 依賴關係

```text
S0 ─┬─> S1 ─────────────────┬─> S7
    └─> S2 ──> S3 ──> S4 ──┬┴─> S5 ──> S6 ──> S7
                           └──> S9
                            S3 ──> S8
```

S1 與 S2 可並行。S8 只要 S3 完成即可開始，不必等 S5–S7。

## 本階段做不到的事

以下即使寫了程式碼也**無法驗證**，本階段不排入，避免產生「寫完了」的錯覺：

| Issue | 為何做不到 |
| --- | --- |
| #8 無頭 USB 權限與 RC-N3 attach | 需要真板與 RC-N3；權限策略選擇依賴 G520 Android build 的實際行為 |
| #9 MSDK 在 G520 註冊啟用 | 需要真板、RC-N3、飛機；本案關鍵風險，不可用 emulator 或 mock 替代 |
| #6 野外網路拓樸 | 需要真板才能驗證 Ethernet／Wi-Fi AP／RNDIS 的實際可用性 |
| #7 影像鏈的真機訊源部分 | 需要飛機提供真實 RTMP 訊源 |
| #11 部署與維運的真板部分 | 安裝形態依賴 #8 的權限策略；安全關機 SOP 需真板實測 |

## 證據紀律

本階段所有產出都是 **mock 與 emulator 證據**。依既有紀律，這些都不能升級任何
硬體能力狀態：mock 行為、可編譯、單元測試通過、issue 關閉，一律不構成硬體
證據。`docs/capability-matrix.md` 的硬體列在 G520 到手並產出第一手證據之前，
全部維持 `UNKNOWN`。

週末結束時的正確說法是「控制面與操作介面在 mock 下可運作」，不是「系統可以
操作 Mini 4 Pro」。

最後更新：2026-08-14。
