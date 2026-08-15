# Architecture

## 目標與邊界

在 G520（MediaTek Genio 520，已刷 Android）機載電腦上運行 DJI Mini 4 Pro
的無頭（headless）控制代理。G520 沒有觸控螢幕，操作者以瀏覽器連入 G520
上的 web server 進行監看與操作。

本 repo 沿用 `drone-agent-android` 的證據紀律：

- `config/capability-matrix/g520-stack.json` 是本 stack 唯一的機器可讀硬體證據來源；
  `docs/capability-matrix.md` 是由它 deterministic 產生並由測試鎖住的 review view。
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
  │    ├─ console-adapter-mock ── Mac／Android mock flavor 共用 execution + snapshot
  │    ├─ gateway ── 維持 client，連出去接 drone-platform（協定不動）
  │    ├─ adapter-dji（MSDK V5）／adapter-mock（product flavor 隔離，沿用）
  │    ├─ vision-opencv-* ── OpenCV backend + desktop/Android native runtime
  │    └─ companion-owned console／lifecycle evidence logging
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

本 repo 自有的 `capability-matrix` 是 pure-JVM 模組：載入並 fail-closed 驗證上述 canonical
JSON、投影上游五個 `core` capability，並產生 Markdown。`console-server` 讀取 bundled
immutable snapshot；`web-console` build 複製同一 JSON。即時 adapter／connection／
actuation lock／lease 狀態是另一份 runtime 資料，不得覆寫 evidence status。

`console-adapter-mock` 也是 pure-JVM；它持有 Mac runner 與 Android mock flavor 共用的
command executor、snapshot provider 與明確標示為 simulation 的 RTH port。Android common
source set 不依賴 mock；只有 `mockImplementation` 與 `src/mock` composition 可看見它，避免
未來 DJI artifact 靜默包入 mock 執行路徑。

## Weekend console 實作切片

瀏覽器 console 使用本 repo 自有的 versioned wire contract，與凍結的
`contracts/agent-protocol/` 完全分離。v1 固定 18 種 message type（client 7、server 11），
Kotlin 與 TypeScript 共讀 canonical fixtures 與 digest；decoder 對方向、欄位、數值語意、
64 KiB frame 上限與 JSON nesting 深度均 fail-closed。瀏覽器 payload 無法提供或
覆寫 authority decision、adapter、aircraft connection、actuation lock 或 operating profile。

JVM runner 已選用 Ktor CIO，並實作靜態 SPA 與 `/api/console/v1` WebSocket。Android
mock flavor 也已接上同一 server、content-addressed SPA assets 與 runtime composition；
目前只有 Android source/targeted compile 證據，Ktor engine compatibility、APK 體積、
boot/restart 仍須 frozen API 34 emulator lane。即使 emulator 通過，也不代表 G520 可用。
localhost profile 另有以下邊界：

- server 固定允許的 browser `Origin`，不從請求 `Host` 推導；missing、`null`、
  錯 host/port 或重複 `Origin` 在建立 session 前即拒絕；
- 靜態回應以 CSP `frame-ancestors 'none'` 與 `X-Frame-Options: DENY` 阻擋
  clickjacking，並拒絕 web root 與任一 ancestor symlink escape；
- handshake 採 `WAITING → HANDSHAKING → READY`，保證 `server_hello` 為第一筆且
  handshake 期間狀態變更不會讓新 client 永久 stale；
- 單一 operator lease 是全域事實；`HELD`、`RELEASED`、`EXPIRED` 廣播給
  READY clients，`DENIED` 只是 requester receipt，不會覆寫 UI 的全域 lease truth。
- CIO 關閉 address reuse，且 `start(false)` 以 instance-specific readiness token 確認
  自己確實擁有 connector；port collision 不得留下「running」假狀態。

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
辨識目標已選定為「亮色地面上的深色膠帶 segmentation」：它直接沿用既有
`LuminanceFrame`／`Segmenter` contract，可在 deterministic fixture 上驗 native
execution，後續也能接到 decoded NV21 frame 與 centerline／tracking pipeline。這個
選型不是物件偵測或自主飛行能力聲明。

runtime 選型固定在 OpenCV 4.9：Mac 使用 `org.openpnp:opencv:4.9.0-0` 與 desktop
loader；Android 使用官方 Maven AAR `org.opencv:opencv:4.9.0`、
`OpenCVLoader.initLocal()` 與 arm64-v8a packaging。兩個平台必須執行同一個
32×24、六像素寬膠帶的 native self-test fixture，回報版本、native build-information
SHA-256、segmentation latency 與 mask IoU。這只證明 loader／演算法／產物接線；在
G520 decoded-frame evidence 出現前，`opencv_on_device_recognition` 仍維持
`UNKNOWN`。RTMP → MediaMTX → WHEP 是給人眼觀看的另一條鏈，不得把 WHEP player
當作 CV input。

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
   operational profile 不得把 `UNKNOWN` 當作已確認能力。目前 mock RTH 是
   companion-owned、可觀察的 simulation port，會讓 mock telemetry 顯示
   `RETURNING_HOME`；上游 `adapter-mock` 並沒有 RTH action port，因此這不是
   DJI/G520 RTH 證據。
3. **連續控制 fail-closed。** virtual-stick 採單一 operator control lease、命令
   sequence/TTL 與 server-side dead-man timeout；瀏覽器放開控制、失焦、斷線或
   lease 失效時，server 必須主動送出 neutral，不能保留最後一次輸入。
   takeoff、landing 與 RTH 在離散動作前也必須先建立 neutral barrier；致動
   timeout、readiness loss、release、disconnect 與 server stop 均必須以新的
   neutral invocation 處理，不能重用已完成或已失敗的 barrier。
4. **Weekend MVP 不建立帳號系統。** Mac runner 只綁 `127.0.0.1`；G520 初次
   commissioning 只綁點對點 Ethernet 介面。Windows 與 G520 使用固定 IP，且 Windows
   不得開啟 Internet Connection Sharing 或 network bridge。進入共享、無線或可路由
   網路前，必須先完成 #5 的認證與傳輸安全。
5. **狀態必須如實呈現。** UI 不需要固定的「MOCK DEMO」橫幅，但必須顯示目前
   adapter（Mock／DJI）、aircraft connection 與 actuation lock 狀態。
6. **Evidence logging 不可關閉。** 命令、authority 決策、control lease、neutral
   safety action 與 commissioning 狀態轉換一律落地可回收的審計紀錄。
   admitted/completed 紀錄含 server-owned `authorityDecisionId`、full-intent digest 與
   result；client-triggered neutral 另保留 release/cancel/blur/page-hide reason。必要稽核
   寫入失敗時必須鎖住致動、撤銷 lease 並 neutral，不得繼續回報成功。
7. **Runtime readiness 為 server-owned gate。** command/control 在 admission 前、commit 時與
   executor 前均重驗 adapter、aircraft connection、actuation lock、operating profile 與
   monotonic readiness epoch。Mock 只在 localhost + connected + unlocked 放行；DJI 只有
   受控 hardware commissioning allowlist 可放行，一般 operational profile 預設拒絕。
8. **Android process recovery 不是 aircraft failsafe。** foreground service、server dead-man
   與 mock/DJI composition 位於同一 `:agent` process；該 process 死亡後，app 本身不可能再
   發 neutral。`START_STICKY` 只恢復 host，且沒有固定 SLA。進 DJI commissioning 前，必須
   以第一手真機 evidence 證明 adapter／aircraft 在 input/process loss 時回 neutral 或安全
   模式；否則致動維持鎖住。
9. **Force-stop 必須維持停止。** Android `force-stop` 會把 package 設成 stopped state，
   app 內 receiver、alarm、service 或 watchdog 都不能解除。一般 process death 是正向
   recovery 測試；force-stop 是負向安全測試，只有使用者或外部 supervisor 的明確啟動可
   恢復。安全停止 action 必須在 stopForeground/stopSelf 前 bounded neutral、close 並 fsync
   evidence。

## 已決定的網路拓樸

初期唯一拓樸是 Windows 筆電透過 USB 轉 RJ-45 與網路線直連 G520。#6 負責在
G520 Android 上驗證固定 IP、指定介面 bind、開機可達性與 WebSocket 穩定性，不再
比較 Wi-Fi AP 或 USB RNDIS。

## Open decisions（各自有對應 issue）

1. **MediaMTX 位置**：G520 機上（aarch64 binary 可行性）vs 地面站筆電。
2. **USB 權限策略**：system/priv-app 自動授權 vs 一次性人工授權後記憶。
3. **web console 產品化認證與傳輸安全**：區網 TLS（自簽憑證）與 token 佈建方式。
4. **G520 fresh-install 第一次啟用**：emulator 可由同 candidate 的 test instrumentation
   明確解除 stopped state，但 production APK 無 Activity，receiver／service 皆
   `exported=false`。#8 必須選定 system image、Device Owner、privileged installer 或受控
   supervisor，並以真機證據證明一次性 commissioning 不會開出一般網路命令旁路。

最後更新：2026-08-16。
