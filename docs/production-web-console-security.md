# Production Web Console 安全基礎

## 狀態與證據邊界

本文件定義 issue #5 第一個 fail-closed 準備切片，**不會**開啟 shared、wireless 或
routable production listener。目前唯一可建立 listener 的 cleartext profile 只有只綁
loopback 的 localhost development。

repo-owned commissioning network policy 目前只能產生不可執行的 endpoint candidate；在
live Android `Network` pin 與同一 inventory generation 的 freshness revalidation 完成前，
point-to-point commissioning 也不能建立 listener。

在本文件列出的 blocking work 全部有實作與 runtime evidence 前，
`SHARED_ROUTABLE_PRODUCTION` 維持不可使用。Admission kernel 的單元測試不是 TLS、
credential provisioning、Windows trust、G520 或硬體證據；G520 capability matrix 因此仍
全部維持 `UNKNOWN`。

## 資產與信任邊界

| 資產 | 擁有者 | 邊界 |
| --- | --- | --- |
| 飛行器命令 authority | `ConsoleServerCore` | 認證不得繞過 command admission、authority、lease、neutral 或 audit。 |
| Operator bearer token | deployment/operator | 只能是 runtime input；不得進 URL、Gradle、manifest、BuildConfig、audit、exception detail 或 repo。 |
| Credential verifier material | deployment | Server-owned immutable digest 設定；client input 不可決定 role、expiry 或 principal。 |
| TLS private key 與 certificate | deployment | 本切片尚未實作；沒有未來 protected keystore 與 trust workflow 時 shared profile 不可啟動。 |
| Session identity | transport/server | Transport 產生的 session ID 仍是 authority identity；browser 欄位不可取代。 |
| Commissioning network binding | `commissioning-network` policy | Accepted binding 只可投影成 non-runnable candidate；caller 不可自行建立／複製後覆寫 bind address 或 Origin。 |
| Command 與 authentication audit | server-owned durable sink | 必要 admission evidence 失敗時 fail closed；永遠不記錄 credential bytes。 |

## Threat model

### 網路攻擊者與 bearer replay

Bearer token 讓任何持有者取得權限，因此 RFC 6750 要求 bearer token 使用受保護的 TLS
channel。本 repo 不會把 digest verifier、exact Origin 或隔離 commissioning cable 當成
shared network 上 TLS 的替代品。

因此：

- localhost development 只能在 loopback 上使用 cleartext；
- point-to-point commissioning 即使通過 `/30` preflight，也只能形成不可執行 candidate；
- 未來只有在同一啟動交易完成 live Android `Network` pin、fresh inventory revalidation 與
  實體隔離檢查後，才可考慮使用 cleartext；
- 目前 server 不能建立 shared/routable profile；
- 未來 browser 必須先驗證部署的 certificate chain，才可送出 token；
- token 不得進 URL，也不得放入可能以 cleartext 傳送的 cookie。

參考：[RFC 6750](https://www.rfc-editor.org/rfc/rfc6750) 與
[Ktor server SSL configuration](https://ktor.io/docs/server-ssl.html)。

### Cross-site WebSocket 與 browser Origin 混淆

既有 exact `Origin` 比對仍是必要邊界，且必須在 WebSocket upgrade 前完成。認證是額外
防線，不是 Origin validation 的替代品。Wildcard host、`localhost` alias、user-info、
query、fragment 或 caller 自選 Origin 都不是 production 設定輸入。

### 先認證、後觀測

需要認證的 composition 必須由單一 atomic factory 同時建立 required hello metadata 與
enforcing decorator；兩者不得成為可分離的公開旋鈕。它必須先驗證 `client_hello` 並完成必要 audit，inner protocol handler
才可建立 core session 或送出 runtime、telemetry、capability、health、lease snapshot。
認證失敗會關閉 transport，且不得建立 core session。Credential 在 hello 交給既有
transport-independent adapter 前必須移除。

Observer principal 可以接收經認證的觀測資料，但不能 acquire／renew lease、release
別人的 lease、送 discrete command、continuous-control frame 或 control neutral。這些
訊息必須在 core 前拒絕並 audit。Operator principal 也沒有特權旁路；既有單一 operator
lease 與全部 command/admission 檢查仍是唯一 authority。

### Credential 洩漏

初版 verifier 只接受固定格式的高 entropy bearer token，並對每一筆 configured digest 使用
constant-time comparison
比對 domain-separated SHA-256 digest。Server 在 hello 結束後不保存呈現的 token。
`AuthenticationPresentation.toString()` 維持 redacted；authentication audit 只包含 bounded、
server-owned outcome／reason。

這仍不是 provisioning lifecycle。未來 credential file／keystore loader 必須是 bounded、
owner-only、no-follow、strict-schema 的 runtime input。Operator UI 只能把 raw token 放在
memory；禁止 `localStorage`、URL、command line、build artifact 或 public evidence bundle。

### Audit failure 與 denial of service

Authentication success 與 authorization refusal 都是 required audit event。若必要 audit
persistence 失敗，request 必須被拒絕，authority-bearing message 不得進 core。
Authentication failure 也會嘗試寫入不含 credential 的 bounded audit，並一律關閉 session；
audit failure 永遠不能把無效 credential 變成可通行。

本切片尚未實作 network rate limit 或 lockout，這也是 shared listener 維持 disabled 的
理由之一。未來設計必須依 bounded transport identity 限速，不得把 client 可偽造欄位當成
trusted identity，也不能讓攻擊者無限期鎖死 operator。

## Profile policy

### `LOCALHOST_DEVELOPMENT`

- 只接受 exact loopback bind；
- browser Origin 由 server 推導為 exact `http://`；
- authentication disabled；
- 只產生 mock／local development evidence。

### `POINT_TO_POINT_COMMISSIONING`

- `CommissioningNetworkPolicy` 的 immutable accepted result 只能建立 non-runnable candidate；
- candidate 固定 exact numeric bind address 與 exact `http://<g520-ip>:8080` Origin，但不能
  傳入 `ConsoleServerConfig`；
- activation API 在 live Android `Network` pin 與 inventory-generation freshness gate 完成前
  固定 fail closed；
- Ktor socket peer address filter 只作 defense-in-depth，不取代 Android `Network` pin、
  firewall/listener inspection 與其他介面的 negative reachability probe；
- cleartext 例外只適用隔離實體網路線與已檢查的 Windows/G520 `/30`；
- 不可 fallback 到 Wi-Fi、wildcard、hostname、DNS、gateway、ICS 或 bridge；
- 在取得 Windows 與 G520 第一手證據前，不是 hardware-verified。

### `SHARED_ROUTABLE_PRODUCTION`

本切片不可使用，且必須在建立 Ktor engine／socket 前失敗。以下項目全部一起存在後，
才可考慮啟用：

1. HTTPS/WSS-only connector、受保護 key material 與文件化的 Windows browser trust ceremony；
2. strict runtime credential provisioning、owner-only storage、generation／rotation、revocation
   與 recovery procedure；
3. active-session expiry／revocation 會立即停止 broadcast、撤銷 operator lease 並進入既有
   neutral 路徑；
4. authenticated HTTP/bootstrap 設計，讓未認證 peer 只能取得刻意公開的 health response；
5. authentication failure、authorization refusal、expiry、revocation audit，以及 bounded
   retention／retrieval；
6. brute-force／rate-limit 與 restart／recovery 測試；
7. 真實 TLS/browser/runtime test 證明不存在 cleartext telemetry 或 command surface。

## Review checklist

- [ ] 沒有 generic constructor 或 generated `copy` 可自行建立 LAN cleartext server config。
- [ ] Localhost 只接受 loopback address。
- [ ] Commissioning 只產生 policy-owned immutable candidate，且在 live Network/freshness gate
      完成前不可建立 server config 或 listener。
- [ ] Shared/routable production 在 engine／socket 建立前失敗。
- [ ] Required hello metadata 與 enforcing decorator 只能由單一 factory 原子建立；raw adapter
      沒有 security mode 旋鈕。
- [ ] 無效或缺少 credential 時不呼叫 inner handler、不建立 core session。
- [ ] Authentication success audit 早於 inner hello handler。
- [ ] Observer authority-bearing message 在 core 前拒絕並 audit。
- [ ] Test、log、error、build input、URL、evidence record 都不含 raw token。
- [ ] Mock／emulator 結果不修改 capability matrix。

## Issue #5 尚未完成的工作

此 foundation 在 pure-JVM lane 最多可達 `TESTED`。Issue #5 必須保持 open，直到 shared
profile 實際完成，並由目標部署環境證明 TLS、credential provisioning／rotation、session
expiry／revocation、authenticated HTTP／WebSocket behavior、browser trust 與 failure audit。
