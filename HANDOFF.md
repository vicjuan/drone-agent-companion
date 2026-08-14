# 交接文件：drone-agent-companion 初期開發

給接手本 repo 的 AI agent。本文件假設你沒有任何先前對話脈絡。**開工前請完整
讀完本文件與 `AGENTS.md`。**

## 1. 任務背景

把 `drone-agent-android`（在 Pixel 8 Pro 上透過 DJI MSDK V5 操作 DJI Mini 4 Pro）
移植到 **G520 機載電腦**（MediaTek Genio 520）上執行。

兩個關鍵差異：

1. G520 目前跑 Ubuntu 24.04，預定 **2026-08-17（週一）** 重刷成 Android。
   刷機由人類執行，不是你的工作。
2. G520 **沒有觸控螢幕**。原本的 Compose UI 改為：G520 上跑一個 web server，
   操作者用瀏覽器連進去監看與操作。

## 2. 現況盤點（2026-08-14）

以下是可查核的事實，不是計畫：

### 本 repo（drone-agent-companion）

- GitHub：`vicjuan/drone-agent-companion`（private），本機 `~/git/drone-agent-companion`。
- `main` HEAD = `a2bfe33`，已推送。
- **內容只有三個文件檔，沒有任何程式碼**：

  ```text
  README.md
  docs/architecture.md          系統架構與安全邊界
  docs/implementation-order.md  撰寫順序（你的工作清單）
  ```

- 尚未建立：Gradle 檔案、submodule、任何模組、任何 CI 設定。你要從零開始。
- Issue #1–#12 已開，對應 GitHub Project #2「Drone Agent Companion (G520)」，
  全部為 `Todo`。

### 參考 repo（drone-agent-android）

- 本機 `~/git/drone-agent-android`，GitHub `vicjuan/drone-agent-android`。
- **注意**：本機目前在 `fix/issue-228-decouple-takeoff-dispatch` 分支，且有一個
  尚未推送的 commit `1f114c7`。`origin/main` 停在 `9dd2417`。
  **submodule 必須 pin 到 `origin/main` 上已推送的 commit**，不要 pin 到本機
  分支或未推送的 commit，否則別人 clone 不下來。
- 該 repo 有 `AGENTS.md`，其中的「AI Agent 完成聲明與驗證守則」與「Android
  昂貴驗證規則」**對本 repo 同樣具強制力**。

### 硬體

- G520：不在手邊，未刷 Android。
- RC-N3、Mini 4 Pro：不在手邊。
- 因此本階段**所有**工作只能用 `adapter-mock` 與 Android emulator 驗證。

## 3. 你要做的事

依 [`docs/implementation-order.md`](docs/implementation-order.md) 的 S0 至 S9 順序執行。
該文件對每一步都寫了範圍、完成判準與對應 issue，此處不重複。

從 **S0（建立 Gradle workspace，issue #1）** 開始。

S0 之前，先做一件事：把 `docs/architecture.md` 與 `docs/implementation-order.md`
完整讀過。架構決策已經定案，你的工作是實作，不是重新設計。若你認為某個決策是
錯的，先提出來讓人類裁決，不要逕行改動。

## 4. 不可跨越的邊界

違反以下任一項，工作即使能跑也要退回重做。

### 4.1 `drone-agent-android` 是唯讀依賴

以 git submodule + Gradle composite build 引用，**不複製原始碼、不 fork、不在
submodule 內直接修改或 commit**。若實作過程發現該 repo 需要新的接縫（例如
`gateway` 要多開一個 interface），停下來向人類回報，由人類決定是否在該 repo
另開 PR。

### 4.2 凍結契約不得更動

`contracts/agent-protocol/` 是與 `drone-platform` 後端的跨 repo 凍結契約，由
`CANONICAL.sha256` 與 `scripts/check-contract-fixture-sync.sh` 鎖住。
**不得為了瀏覽器 UI 的需求增刪其欄位。** 瀏覽器用本 repo 自有的 console 協定，
兩者共用 `core` 狀態模型與 `gateway.admission` 政策物件，不共用 wire format。

（這是本 repo 初版架構文件寫錯過的地方，已更正。若你看到任何殘留的「把
gateway 翻轉成 server」描述，那是舊敘述，以本節為準。）

### 4.3 命令一律走 admission

不得存在任何繞過 `CommandAdmissionPolicy` 的 HTTP 或 WebSocket 端點。瀏覽器
console 是一個受認證的 client，不是特權通道。

### 4.4 致動 fail-closed

致動類命令（takeoff、virtual stick、RTH、landing）在本階段**必須在 server 與
UI 兩層都拒絕**。只把 UI 按鈕藏起來不算數。開放條件是 capability matrix 中該列
有本 stack 的第一手硬體證據 — 本階段不可能有。

### 4.5 證據紀律

`AGENTS.md` 的八級驗證狀態不得跳級，證據不足時 fail closed。本階段的**天花板**：

| 能達到 | 不可能達到 |
| --- | --- |
| `TESTED`、`ARTIFACT_VERIFIED`、`RUNTIME_VERIFIED`（Mac 或 emulator 環境） | `HARDWARE_VERIFIED` |

mock 行為、可編譯、單元測試通過、issue 關閉，**一律不構成硬體證據**，不得用來
把 capability matrix 的任何一列從 `UNKNOWN` 升級。

正確說法是「控制面在 mock 下可運作」，不是「系統可以操作 Mini 4 Pro」。

## 5. 環境

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
```

本機預設 JDK 是 25，會讓 Gradle 8.7 失敗。**每個新 shell 都要先設定**，這是最常
見的假故障來源。

Android SDK 在 `~/Library/Android/sdk`。`drone-agent-android` 的 Gradle 會透過
`ANDROID_HOME`、`ANDROID_SDK_ROOT` 或 `local.properties` 的 `sdk.dir` 偵測，
偵測不到就只納入純 JVM 模組 — 這是設計行為，不是錯誤。

## 6. 已知陷阱

| 陷阱 | 說明 |
| --- | --- |
| DJI jar 在 JVM 測試觸發 VerifyError | 廠商型別必須留在 adapter 那側。`console-server` 保持 pure JVM，不得引用 Android API 或 MSDK 型別 |
| Android 沒有 WebSocket server | `com.sun.net.httpserver` 在 Android 不存在，OkHttp 只有 client。S3 需選型（建議 Ktor CIO，**但必須實測 Android 相容性與 APK 體積後才算數**） |
| emulator ≠ G520 | emulator 通過不代表真板通過。開機自啟、廠商省電策略、foreground service 是否被回收，全部要在真板重驗，matrix 維持 `UNKNOWN` |
| RTMP ingest 路徑 | 單一路徑段等於 `streamId`，**不要**用 `/live/<key>`，那樣不會匹配後端與 WHEP 路徑 |
| 昂貴驗證重跑 | `AGENTS.md` 的 frozen candidate gate 有強制力：完整 Android suite、assembly、lint、emulator 啟停，每個 candidate 每個 lane 最多跑一次 |
| 編輯 GitHub issue body | 本專案曾發生 issue body 被加入 BOM、em dash 被轉成 `?` 的編碼損壞。若要編輯 issue，先取原始 body 比對，不要盲改 |

## 7. 工作流程與交付

- 每個 S 步驟開一個 branch，PR 連結對應 issue，合併後把 Project #2 的該項移到
  `Done`。
- commit message 說明**為什麼**，不只是做了什麼。
- 每次收尾依 `AGENTS.md` 的「強制完成回報格式」列出：目標、最高已驗證狀態、
  原始碼證據、測試證據、產物證據、部署證據、執行期/硬體證據、尚未驗證、結論。

## 8. 需要人類裁決，不要自行決定

- 架構決策的變更（見第 3 節）。
- `drone-agent-android` 需要修改時（見 4.1）。
- S3 的 WebSocket server 選型若實測發現 Ktor 不可行，備案選擇。
- `docs/architecture.md` 中列為 open decisions 的四項（MediaMTX 位置、網路拓樸、
  USB 權限策略、認證與傳輸安全方案）— 這四項有的需要 G520 到手才能定，不要
  用臆測填補。
- 任何會讓 capability matrix 狀態升級的判斷。

## 9. 本階段做不到的事

issue #6、#8、#9，以及 #7、#11 的硬體部分，**在 G520 到手前無法驗證**，不要
排進來。詳見 `docs/implementation-order.md` 末節。

其中 **#9（MSDK 能否在 G520 註冊啟用）是整個專案的關鍵風險**：若它不成立，
架構要退回備案（外掛一支 Android 手機當 MSDK bridge）。本階段所有工作都應該
維持「adapter 可替換」的接縫，不要寫出只有 G520 直連才成立的假設。

最後更新：2026-08-14。
