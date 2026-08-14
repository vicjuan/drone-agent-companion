# Architecture

## 目標與邊界

在 G520（MediaTek Genio 520，已刷 Android）機載電腦上運行 DJI Mini 4 Pro
的無頭（headless）控制代理。G520 沒有觸控螢幕，操作者以瀏覽器連入 G520
上的 web server 進行監看與操作。

本 repo 沿用 `drone-agent-android` 的證據紀律：

- `docs/capability-matrix.md`（本 repo 版本）是本 stack 唯一的硬體證據來源。
- Pixel 8 Pro stack 的 `CONFIRMED` **不得**轉移到本 stack。USB host 控制器、
  Android build、kernel 都不同；Mini 4 Pro + RC-N3 + G520 (Android) 的每一列
  硬體狀態自 `UNKNOWN` 起跳，直到本 stack 第一手證據存在。
- Mock 行為、可編譯、單元測試、ticket 關閉，一律不能升級硬體狀態。

## 系統構成

```text
Windows 操作者筆電瀏覽器
  │  SPA：儀表板 + 操縱介面 + WHEP 影像播放
  │  USB 轉 RJ-45 + 點對點 Ethernet（固定 IP）
  │  HTTP/WebSocket
  ▼
G520（Android，無螢幕）
  ├─ host-headless（Android app）
  │    ├─ 開機自啟 foreground service，無 Activity UI 依賴
  │    ├─ console-server ── 靜態 SPA 與 WS 端點，命令送進既有 admission 政策
  │    ├─ gateway ── 維持 client，連出去接 drone-platform（協定不動）
  │    ├─ adapter-dji（MSDK V5）／adapter-mock（product flavor 隔離，沿用）
  │    ├─ vision-opencv-* ── OpenCV backend + desktop/Android native runtime
  │    └─ evidence logging（沿用）
  ├─ 影像鏈：RTMP → MediaMTX → WHEP（MediaMTX 位置待決，見 open decisions）
  │  USB
  ▼
RC-N3 ──────► DJI Mini 4 Pro
```

## 模組重用策略

`drone-agent-android` 以 git submodule 進入本 repo，Gradle composite build
（`includeBuild`）引用其模組；不複製原始碼，不 fork。

| 既有模組 | 角色 |
| --- | --- |
| `core` | 直接重用：vendor-neutral domain model 與 safety contracts |
| `drone-actuation` | 直接重用：本地控制契約、arbitration、watchdog policy |
| `gateway` | 直接重用且**不擴充 wire 協定**：它實作與 drone-platform 凍結的 Phase-0 agent protocol（由 `contracts/agent-protocol/CANONICAL.sha256` 鎖住），維持 client 角色。瀏覽器由本 repo 自有的 console 協定服務；兩者共用 `core` 狀態模型與 `gateway.admission` 政策物件，不共用 wire format。詳見 [`implementation-order.md`](implementation-order.md) |
| `vision`、`drone-observation` | 直接重用 |
| `adapter-dji`、`adapter-mock` | 直接重用；flavor 隔離規則照舊 |
| `app`（Compose host） | 不重用 → 本 repo 的 `host-headless` |
| `app-debug-ui` | 不重用 → 本 repo 的 `web-console`（browser SPA） |

## OpenCV on-device 視覺決策

`drone-agent-android:vision` 已有 `TapeSegmenter` 等 OpenCV 程式碼，但 OpenCV desktop
artifact 是 `compileOnly`／`testImplementation`；Android runtime 刻意使用 pure-Kotlin
fallback，APK 並沒有 OpenCV native runtime。因此「重用 `vision`」本身不能滿足本案
的視覺需求。

本 repo 新增三個 companion-owned 模組，避免 desktop loader 與 Android natives 混入
同一產物：

- `vision-opencv-core`（pure JVM）：依賴上游 `vision` 的 `LuminanceFrame`、`Segmenter`
  與 result contracts，實作可注入 native initializer 的 OpenCV backend；
- `vision-opencv-desktop`（JVM runtime）：只供 Mac fixture/replay 與 runner smoke，提供
  desktop native loader／runtime dependency；
- `vision-opencv-android`（Android library）：只供 `host-headless`，封裝 OpenCV Android
  distribution、arm64 native packaging 與 Android initializer；
- Android host 從 `drone-observation` 的 `DecodedFrameStream` 取得 vendor-neutral decoded
  frames，轉成 `LuminanceFrame` 後實際執行 OpenCV 前處理、segmentation、feature／
  target recognition 或 tracking；
- 以 vendor-neutral result model 將結果送回 host、evidence 與 console；
- 讓 OpenCV `Mat`、loader 與平台型別留在此邊界內，不進入 `core`、console protocol
  或 command admission。

Mac 上的 OpenCV fixture/replay 測試只能證明演算法；完成條件必須包含 G520 Android
產物內的 native library 識別，以及 runtime log／result 證明實際呼叫 OpenCV。初始
辨識目標與 OpenCV Android distribution 選型由 #14 落實，但「on-device 必須使用
OpenCV」不是 open decision。RTMP → MediaMTX → WHEP 是給人眼觀看的另一條鏈，
不得把 WHEP player 當作 CV input。

沿用的機械檢查（vendor-neutral guard、APK boundary guard、
closed-loop-not-executed guard）隨 submodule 一併生效，CI 必須執行。

## 安全與 commissioning 邊界

1. **Strict command authority 不因 UI 換成瀏覽器而放寬。** 瀏覽器 console 不是
   特權通道；其命令一律進入既有
   `CommandAdmissionPolicy` 與 authority/audit 路徑，不存在繞過 admission 的
   HTTP 或 WebSocket 致動端點。
2. **Mock 可執行不等於真機可執行。** Weekend MVP 允許 takeoff、landing、RTH 與
   virtual-stick 命令在 `adapter-mock` 下端到端執行。真機啟動時預設鎖住致動，
   只能由明確的 hardware commissioning session 逐項開放並產生第一手證據；一般
   operational profile 不得把 `UNKNOWN` 當作已確認能力。
3. **連續控制 fail-closed。** virtual-stick 採單一 operator control lease、命令
   sequence/TTL 與 server-side dead-man timeout；瀏覽器放開控制、失焦、斷線或
   lease 失效時，server 必須主動送出 neutral，不能保留最後一次輸入。
4. **Weekend MVP 不建立帳號系統。** Mac runner 只綁 `127.0.0.1`；G520 初次
   commissioning 只綁點對點 Ethernet 介面。Windows 與 G520 使用固定 IP，且 Windows
   不得開啟 Internet Connection Sharing 或 network bridge。進入共享、無線或可路由
   網路前，必須先完成 #5 的認證與傳輸安全。
5. **狀態必須如實呈現。** UI 不需要固定的「MOCK DEMO」橫幅，但必須顯示目前
   adapter（Mock／DJI）、aircraft connection 與 actuation lock 狀態。
6. **Evidence logging 不可關閉。** 命令、authority 決策、control lease、neutral
   safety action 與 commissioning 狀態轉換一律落地可回收的審計紀錄。

## 已決定的網路拓樸

初期唯一拓樸是 Windows 筆電透過 USB 轉 RJ-45 與網路線直連 G520。#6 負責在
G520 Android 上驗證固定 IP、指定介面 bind、開機可達性與 WebSocket 穩定性，不再
比較 Wi-Fi AP 或 USB RNDIS。

## Open decisions（各自有對應 issue）

1. **MediaMTX 位置**：G520 機上（aarch64 binary 可行性）vs 地面站筆電。
2. **USB 權限策略**：system/priv-app 自動授權 vs 一次性人工授權後記憶。
3. **web console 產品化認證與傳輸安全**：區網 TLS（自簽憑證）與 token 佈建方式。
4. **OpenCV 實作選型**：Android distribution／native packaging 方式與第一個可驗收的
   visual-recognition target（#14）；使用 OpenCV 本身已定案。

最後更新：2026-08-14。
