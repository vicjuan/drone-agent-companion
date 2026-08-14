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
操作者筆電/手機瀏覽器
  │  SPA：儀表板 + 操縱介面 + WHEP 影像播放
  │  HTTP/WebSocket（區網）
  ▼
G520（Android，無螢幕）
  ├─ host-headless（Android app）
  │    ├─ 開機自啟 foreground service，無 Activity UI 依賴
  │    ├─ gateway ── server mode，沿用既有命令契約與 strict authority
  │    ├─ adapter-dji（MSDK V5）／adapter-mock（product flavor 隔離，沿用）
  │    ├─ web-console-server ── 靜態 SPA 與 WS 端點
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
| `gateway` | 重用並擴充：新增 server-side hosting（原本只有 client transport） |
| `vision`、`drone-observation` | 直接重用 |
| `adapter-dji`、`adapter-mock` | 直接重用；flavor 隔離規則照舊 |
| `app`（Compose host） | 不重用 → 本 repo 的 `host-headless` |
| `app-debug-ui` | 不重用 → 本 repo 的 `web-console`（browser SPA） |

沿用的機械檢查（vendor-neutral guard、APK boundary guard、
closed-loop-not-executed guard）隨 submodule 一併生效，CI 必須執行。

## 安全邊界（沿用且不得弱化）

1. **Strict command authority 不因 UI 換成瀏覽器而放寬。** 瀏覽器 console
   是 gateway client，命令進入既有 admission/authority/audit 路徑；不存在
   繞過 gateway 的 HTTP 直接致動端點。
2. **致動 fail-closed。** 未經授權路徑與本 stack 證據，閉環輸出維持
   observation-only。
3. **Web console 需認證。** 未認證連線只能取得健康檢查，拿不到 telemetry
   與命令面。
4. **Evidence logging 不可關閉。** 命令、authority 決策、safety action
   一律落地可回收的審計紀錄。

## Open decisions（各自有對應 issue）

1. **MediaMTX 位置**：G520 機上（aarch64 binary 可行性）vs 地面站筆電。
2. **瀏覽器到 G520 的網路拓樸**：Ethernet 直連、G520 開 Wi-Fi AP、或
   USB RNDIS。
3. **USB 權限策略**：system/priv-app 自動授權 vs 一次性人工授權後記憶。
4. **web console 認證與傳輸安全**：區網 TLS（自簽憑證）與 token 佈建方式。

最後更新：2026-08-14。
