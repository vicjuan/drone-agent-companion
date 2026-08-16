# Mac synthetic RTMP → MediaMTX → WHEP runtime evidence

## 結論與範圍

2026-08-16（Asia/Taipei）在 Mac 上完成 issue #7A 的 synthetic playback
垂直切片：

```text
stdlib Python RGB clock → ffmpeg H.264/RTMP → MediaMTX → WHEP → browser iframe
```

結果只支持目前 candidate 的 **Mac synthetic `RUNTIME_VERIFIED`**。它不支持 DJI
camera、G520 Android、Windows ↔ G520 Ethernet、真機延遲、最終 MediaMTX placement
或硬體 capability promotion。`g520-stack.json` 的 17 列維持 `UNKNOWN`，issue #7
維持開啟。

## Candidate 與環境

- Git commit：`bb87409f6f01203f303a51fe65e341838c2974d3`
- Runtime 在 commit 前執行；執行後只將 byte-identical frozen blobs 建成上述 commit，
  production/test blobs 未再變動。
- 關鍵 blob：
  - controller：`2bdeee8ddf50d3a94dcbdd713a2299470720469e`
  - publisher supervisor：`14f604e8347f9a05a6532a61d847c64e9fe455ba`
  - nonce stop helper：`d746bced8c877fde519cca6491edbc6271a98ac0`
  - MediaMTX config：`37223061759701967f2fbb7a65df03d2cf140e5d`
- Mac：MacBook Pro `Mac14,9`，Apple M2 Pro，16 GB
- macOS：26.5.1（25F80）
- Browser：Codex in-app Chromium；工具未暴露精確 Chromium build
- Docker Desktop engine：29.3.1
- ffmpeg：8.1.2，含 `libx264`
- Python：3.9.6
- MediaMTX：`bluenviron/mediamtx:1.19.1@sha256:61ebddaa43a6da78d4c6e98b9f9c12066856ffd85893656f5c000d870b88bbe4`

## Runtime 觀察

- `verify` 通過 exact owned container／publisher identity。
- Mac listener 僅有：
  - `127.0.0.1:1936/tcp`（RTMP）
  - `127.0.0.1:8891/tcp`（WHEP/player HTTP）
  - `127.0.0.1:8190/udp`（WebRTC ICE）
- MediaMTX log：
  - H.264 publisher online，path 精確為 `mock-main`；
  - WebRTC session `7941a501` 建立；
  - peer connection established；
  - reader 從 `mock-main` 讀取一條 H.264 track。
- Browser iframe 的 `<video>`：`readyState=4`、`paused=false`、`ended=false`、
  `640×360`，`currentTime` 持續增加。
- Console 顯示 `CONFIGURED / SYNTHETIC`；它不被當成 online/playback truth。
- 畫面上 17 個 capability row 全為 `UNKNOWN`。
- 本次未保存 HAR；WHEP runtime 由 MediaMTX WebRTC reader/session log、實際解碼中的
  `<video>` 狀態與持續變動的 fixture frame 共同佐證，不宣稱有獨立保存的 HTTP status／
  Origin transcript。

## Mock control 與影像共存

同一頁取得 mock control lease 後，以鍵盤 Space 對「前進」做一次 press/release：

- 方向按鈕可用；
- 影像 `currentTime` 從 `55.955` 增至 `57.255`，播放未停；
- durable audit 的 `control_admitted`／`control_completed` 具有相同
  `authorityDecisionId=d596b4bb-2a59-4787-ae7d-3317b64cc5a7` 與
  `intentDigestSha256=5fc4fff0caf7bbef7a19f3f7d490047b983c97b9924508e0ea027548e3d5b895`；
- completion outcome 為 `applied`；
- 隨後 `safety_neutral_requested`／`safety_neutral_completed` 的 reason 為
  `client_request`、client reason 為 `operator_release`、outcome 為 `succeeded`；
- 最後釋放 lease。

這只證明 Media UI 沒有 gating／破壞既有 mock authority path，不是 G520 或 aircraft
actuation evidence。

## Steady-state latency 樣本

方法：每次擷取完整 browser PNG，依 fixture 固定七段顯示幾何讀取下排 13 位 source
epoch；observer 使用 screenshot API 完成後的 `Date.now()`。因此下表是
`screenshot completion - visible source epoch`，是包含 19–45 ms screenshot capture
開銷的保守上界，不是精密 photodiode source-to-glass 測量。

| # | source epoch ms | screenshot completion ms | upper bound ms |
| ---: | ---: | ---: | ---: |
| 1 | 1786824579202 | 1786824579290 | 88 |
| 2 | 1786824579537 | 1786824579614 | 77 |
| 3 | 1786824579867 | 1786824579935 | 68 |
| 4 | 1786824580205 | 1786824580280 | 75 |
| 5 | 1786824580538 | 1786824580613 | 75 |
| 6 | 1786824580873 | 1786824580947 | 74 |
| 7 | 1786824581206 | 1786824581281 | 75 |
| 8 | 1786824581540 | 1786824581615 | 75 |
| 9 | 1786824581867 | 1786824581951 | 84 |
| 10 | 1786824582199 | 1786824582285 | 86 |
| 11 | 1786824582537 | 1786824582610 | 73 |
| 12 | 1786824582871 | 1786824582956 | 85 |
| 13 | 1786824583199 | 1786824583285 | 86 |
| 14 | 1786824583538 | 1786824583620 | 82 |
| 15 | 1786824583871 | 1786824583955 | 84 |

- sample count：15
- median：77 ms
- p95（nearest-rank）：88 ms
- max：88 ms
- screenshot capture duration median：32 ms

不可把以上數字轉述成 DJI、G520、production camera 或硬體 E2E latency。

## Cooperative stop ownership

從第二個 shell 執行 `./scripts/media-fixture.sh stop`：

- 只以 `O_EXCL` 建立 active run 的 `stop-request.<64-hex nonce>`；
- 輸出明確表示沒有讀取或 signal numeric PID；
- publisher supervisor 自行消耗 exact nonce request，terminate／reap 自己直接持有的
  Python source 與 ffmpeg children；
- foreground `run` 以 status 0 結束；
- 後續 `status` 顯示 MediaMTX 與 publisher 都為 `ABSENT` 並以 non-zero 結束；
- owned CID、PID、run nonce 與 stop request 全部清除；
- 既有、非本 fixture 擁有的 `drone-platform-mediamtx` container 仍在，未被操作。

本次沒有執行 stop 後重新啟動／既有 iframe 自動恢復測量，因此不宣稱 seamless
reconnect 或 reload-free recovery。

## 驗證摘要

- `./scripts/test-media-fixture-static.sh`：RGB 4／4、publisher 4／4、stop helper
  3／3，全通過；未啟動 runtime。
- `web-console`：typecheck、build、82／82 tests 通過。
- Gradle：`:console-server:test :console-runner:test :host-headless:testMockDebugUnitTest`
  通過。
- `bash scripts/check-companion-boundaries.sh`：通過。
- `git diff --check`／vendor submodule status：乾淨。

## 尚未驗證

- DJI aircraft camera RTMP publisher 與真實 `/live/<key>` policy；
- G520 Android/aarch64 runtime、thermal、power、long-run stability；
- Windows ↔ G520 point-to-point Ethernet、ICE 與 bandwidth；
- production MediaMTX placement；
- isolated publisher loss/recovery、automatic iframe recovery；
- aircraft decoded-frame → OpenCV path；
- `HARDWARE_VERIFIED` 的任何條件。
