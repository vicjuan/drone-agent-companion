# Mac 合成影像 fixture 操作手冊（issue #7A）

這份手冊只驗證沒有 G520／飛機時可完成的鏈路：

```text
stdlib Python RGB clock → ffmpeg H.264/RTMP → MediaMTX → WHEP → browser
```

它不驗證 DJI aircraft-camera RTMP、G520 Android／aarch64、點對點 Ethernet，亦不決定
MediaMTX 最終部署在 G520 或地面站。完成本手冊後，capability matrix 的 **17 列仍全為
`UNKNOWN`**，`stream_capability` 也不得升級；這份 Mac synthetic evidence **不能關閉 issue
#7**。

## 固定資源與網路邊界

| 項目 | 固定值 |
| --- | --- |
| MediaMTX image | `bluenviron/mediamtx:1.19.1@sha256:61ebddaa43a6da78d4c6e98b9f9c12066856ffd85893656f5c000d870b88bbe4` |
| Docker container | `drone-agent-companion-media-fixture` |
| stream path | `mock-main`（不使用 `/live/<key>`） |
| RTMP publish | `127.0.0.1:1936/tcp` → container `:1936` |
| WHEP/player HTTP | `127.0.0.1:8891/tcp` → container `:8891` |
| WebRTC ICE | `127.0.0.1:8190/udp` → container `:8190` |

[`config/media/mediamtx-mac-fixture.yml`](../config/media/mediamtx-mac-fixture.yml)
刻意讓三個 listener 在 **container network namespace** 內使用 wildcard address。Docker
bridge 無法把 host port forwarding 導到只綁 container `127.0.0.1` 的 listener；真正的
Mac exposure 邊界是三個 `--publish 127.0.0.1:host:container`。container 內 wildcard
**不代表** host 對 LAN 公開。腳本禁止 host networking，且 `verify` 會同時比對 Docker
port mapping 與 Mac listener table；任一 listener 不是 `127.0.0.1` 就 fail closed。

RTSP、HLS、SRT、MoQ、API、metrics、pprof 與 playback server 均明確關閉。只允許匿名
publish/read `mock-main`，拒絕第二個 publisher，也不錄影。

## 前置條件與純靜態驗證

需要 Docker Desktop、Python 3.9+、提供 `libx264` 的 ffmpeg、`curl` 與 `lsof`。本機
ffmpeg 不需要 `drawtext`；時間戳由 stdlib-only Python 直接畫成 raw RGB24。

先執行完全不啟動 Docker、MediaMTX 或 ffmpeg 的檢查：

```bash
./scripts/test-media-fixture-static.sh
```

也可只跑 controller 自身的 source/config 檢查：

```bash
./scripts/media-fixture.sh static-verify
```

任何 image digest、port、path、CORS、ownership marker 或 protocol 關閉項目漂移都應先
修正，不得略過檢查直接啟動。

## Foreground 啟動、狀態與 runtime 驗證

Terminal A 執行 `run`，並保持該 process/session 在 foreground：

```bash
./scripts/media-fixture.sh run
```

看到 `RUNNING foreground_owner=...` 後，不要關閉 Terminal A。controller 會持續 `wait`
專屬 publisher supervisor；supervisor 直接持有 Python RGB source 與 ffmpeg 兩個 child，
任一 child 異常離開就收斂另一個並讓整個 fixture fail closed。這個設計不依賴背景程序
跨 shell／unified-exec session 存活。每次 `run` 另產生 64-hex nonce；只有名稱精確為
`stop-request.<nonce>`、內容相同且 mode `0600` 的 request 會被該次 supervisor 消耗。

Terminal B 再執行：

```bash
./scripts/media-fixture.sh status
./scripts/media-fixture.sh verify
```

`run` 只建立帶專屬 label 的固定名稱 container，並記錄它實際的 64 字元 CID 與帶專屬
命令列 marker 的 publisher-supervisor PID。`verify` 必須輸出 `VERIFY PASS`；它證明
image/name/label、supervisor PID、Docker bridge mappings、host listener table 與 player
HTTP page，**不等於**瀏覽器已解碼出畫面。

若要人工複核 host exposure，可查看：

```bash
docker port drone-agent-companion-media-fixture
lsof -nP -a -iTCP:1936 -sTCP:LISTEN
lsof -nP -a -iTCP:8891 -sTCP:LISTEN
lsof -nP -a -iUDP:8190
```

三個 `lsof` 結果只能出現 `127.0.0.1:<port>`。若看到 `*:<port>`、`0.0.0.0:<port>`、
`[::]:<port>` 或非 loopback address，立刻執行本手冊的安全停止命令，該次結果判定失敗。

## Browser acceptance 與 WHEP 證據

先用 MediaMTX 自帶頁面隔離驗證 fixture：

1. 開啟 <http://127.0.0.1:8891/mock-main>。
2. 開啟 Browser DevTools 的 Network，清除舊紀錄後重新載入。
3. 篩選 `whep`，保留成功的
   `POST http://127.0.0.1:8891/mock-main/whep` request／response 證據。request Origin 應為
   `http://127.0.0.1:8891`；console runner 使用 `8080` 或 `18081` 都不需要加入 MediaMTX
   CORS allowlist，因為 WHEP fetch 在 `8891` 的 iframe 內發生。
4. 畫面必須同時有移動中的直條、輪替 phase blocks、上排本機
   `HH:MM:SS.mmm` 與下排 13 位 Unix epoch milliseconds。靜止截圖不能單獨證明持續播放。

再啟動 mock console runner 並開啟 <http://127.0.0.1:8080>：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./gradlew :console-runner:run
```

console 的 `Live video` 區必須如實標為 `CONFIGURED / SYNTHETIC`，顯示相同動態畫面；
DevTools 仍須看見 iframe 內送出的 `/mock-main/whep` POST。`CONFIGURED` 只表示 server
提供的合成訊源設定通過 allowlist，**不表示** MediaMTX online、WHEP session 成功或 browser
正在播放；online/playback truth 必須另由成功 POST 與持續變動的可見 frame 證明。影像
unavailable/error 不得改變命令 admission 或 actuation readiness。

## 延遲紀錄

下排可視 epoch timestamp 是 frame 產生前在 Mac 取樣的 source time。若要取得可重做的
近似 source-to-glass 數字，可直接開啟 `8891` player，在 DevTools Console 加上 observer
clock overlay：

```js
const clock = Object.assign(document.createElement("output"), { id: "observer-epoch-clock" });
Object.assign(clock.style, { position: "fixed", right: "8px", top: "8px", zIndex: "2147483647", color: "white", background: "black", padding: "8px", font: "20px monospace" });
document.body.append(clock);
(function tick() { clock.textContent = `observer epoch ms ${Date.now()}`; requestAnimationFrame(tick); })();
```

截圖需同時包含 source epoch 與 observer epoch；以 `observer - source` 計算近似值，至少取
10 筆，記錄 median、p95 與最大值。這個數字包含 Python render、encode、RTMP、relay、
WebRTC、decode、paint 與截圖相位誤差，應標為 **Mac synthetic approximate
source-to-glass latency**。它不是 DJI camera latency、G520 latency 或 production E2E
保證。另以碼表從執行 `run` 到第一個動態 frame 可見的數字，只能標為
**publisher/fixture startup → first-visible latency**，不可與 steady-state 數字混用。

每次紀錄至少包含：

```text
date/timezone:
git commit + dirty state:
Mac model / macOS:
browser + version:
Docker Desktop / ffmpeg / Python versions:
MediaMTX image + digest:
WHEP POST URL / status / Origin:
10 source epoch / observer epoch pairs:
median / p95 / max (ms):
fixture startup → first-visible (ms, method):
visible motion + phase-block observation:
limitations / anomalies:
```

原始 HAR、截圖或錄影可能包含本機資訊，放在 gitignored `artifacts/` 或
`evidence-private/`，不要提交到 public repo。

## 失流、恢復與安全停止

腳本不提供任意 PID/container 操作，也不要手動 `docker stop`、`docker rm` 或 `kill`。
以下流程安全地覆蓋全 fixture 中斷與恢復：

1. 保持 player 與 Network panel 開啟，確認畫面正在動且已有成功 WHEP POST。
2. 在 foreground `run` 的 Terminal A 按 `Ctrl-C`。controller 會先要求 supervisor 收斂
   source + ffmpeg，再依 CID 移除自己的 container。記錄畫面凍結／錯誤與 WHEP session
   結束。
3. 在 Terminal A 再執行 `./scripts/media-fixture.sh run` 並保持 foreground；Terminal B
   執行 `./scripts/media-fixture.sh verify`。
4. 若既有 player 沒有自行恢復，重新載入 console/page；確認產生新的
   `POST /mock-main/whep`、時間戳回到當前時間且 movement 恢復。
5. 明載「自動恢復」或「需 reload」及恢復秒數，不得把 reload 後恢復寫成無縫 reconnect。

此流程同時中斷 publisher 與 relay，不能冒充 isolated publisher-loss 測試；真正 aircraft
RTMP publisher 消失／恢復仍須在 issue #7 的 G520／飛機階段驗證。

最後回到 Terminal A 按 `Ctrl-C`，再從 Terminal B 確認：

```bash
./scripts/media-fixture.sh status
```

停止後的 `status` 會顯示兩項 `ABSENT` 並以 non-zero 結束，這是「目前沒有可用 fixture」
的預期狀態，不是它又啟動了其他資源。

若 Terminal A 無法互動、但 foreground owner 仍在執行，才從另一個 shell 執行：

```bash
./scripts/media-fixture.sh stop
```

這個跨 shell `stop` **只會**在 mode `0700` 的 state directory 以 `O_EXCL` 建立當次
`stop-request.<nonce>`；它不讀 publisher PID、不比對 argv、不送 signal，也不操作 Docker。
foreground supervisor 自己 polling 精確 request path，命中後 terminate + reap 它親自建立
的 source/ffmpeg children；foreground controller 在 `wait` 返回後才依已驗證 CID 清除自己的
container 與 control state。這避免 stale PID reuse 與 validate→kill TOCTOU。

`stop` 輸出 `STOP REQUESTED` 只表示 request 已建立，不表示 cleanup 已完成；持續以
`status` 確認兩項都成為 `ABSENT`。若 foreground owner 已死亡，request 無人消耗，`stop`
不會越權改成 PID/Docker recovery；保留 state/output 人工釐清，不要手動刪 state file、
`kill` 或移除其他 container。

## 證據天花板

完成 browser acceptance 與數字紀錄，最多只證明目前 Mac candidate 的 synthetic
RTMP → MediaMTX → WHEP runtime。以下仍未驗證：

- Mini 4 Pro camera 是否能依固定單一路徑規則 publish RTMP；
- G520 Android/aarch64 能否承載 relay，及 relay 應部署機上或地面站；
- Windows ↔ G520 點對點 Ethernet 的 throughput、ICE 與失流恢復；
- 真機延遲、長時間穩定性、熱／電力與 process restart；
- aircraft stream decoded-frame → OpenCV input（WHEP 只供人眼觀看）。

因此此 slice 的最高合理狀態是 Mac 環境的 `RUNTIME_VERIFIED`；在尚未實際執行本手冊
前只到 `TESTED`。無論哪一種，均不是 `HARDWARE_VERIFIED`，17 列 target-stack
capability 保持 `UNKNOWN`，issue #7 保持開啟。
