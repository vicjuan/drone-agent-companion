# Capability Evidence Records

`config/capability-matrix/g520-stack.json` 是 G520 stack 的唯一權威資料。狀態更新必須由 PR 修改該檔，並讓 `docs/capability-matrix.md` 的一致性測試通過；runtime 不得自行回寫或升級 matrix。

## Evidence record 必填欄位

- `capturedAt`：UTC RFC 3339 時間。
- `commit`：受測程式的完整 40 字元 Git commit SHA。
- `operator`：可追溯的操作者識別。
- `runtimeProfile`：例如 `g520-hardware-commissioning`，不可只寫 `production`。
- `deviceSetId`：隨機產生的 opaque version-4 ID，只用來引用該次 G520 + RC-N3 + Mini 4 Pro 組合。公開 repo 不存裸序號或可關聯的無 key hash；受保護的原始 evidence manifest 才保存此 ID 對應的完整序號 tuple，reviewer 必須能讀取。
- `rawLogLocation`：`evidence://bundle/<opaque-v4-id>/...` reference。它由受控 evidence registry 解析，不能含 user-info、query token 或其他 credential；reviewer 必須有權限取得對應 bundle。
- `trackingIssue`、`outcome` 與 `summary`：連回 commissioning 條件與可否證結論。

三種環境的 schema 範例在 [`capability-record.examples.json`](capability-record.examples.json)。它們全部明確標為 `TEMPLATE`，不能複製進 production matrix 當作證據。

原始 log、裸序號與 device-set mapping 不得寫入此公開 repo。若本機工具暫存資料，只能放在已忽略的 `/artifacts/` 或 `/evidence-private/`，正式紀錄必須先移入受控 evidence registry。

受保護 registry 內的 manifest 必須符合 [`protected-bundle-manifest.schema.json`](protected-bundle-manifest.schema.json)；安全欄位範例見 [`protected-bundle-manifest.template.json`](protected-bundle-manifest.template.json)。實際 manifest 必須把 public record 綁到原始證據：

- `bundleId` 等於 `rawLogLocation` 的 bundle UUID；`deviceSetId` 與 public record 完全相同。
- `testedCommit`、`capturedAt`、`operator`、`runtimeProfile` 與 public record 完全相同。
- `hardwareSerials` 保存 G520、RC-N3、Mini 4 Pro 三個裸序號，只能存在受保護 registry。
- 每個 raw file 都列 relative path、SHA-256、media type 與 byte length；public `rawLogLocation` 的 path 必須對到其中一列。
- `recordKind: TEMPLATE`、全零 ID/hash、placeholder serial 或零 byte 檔案都不是 production manifest。

## Status promotion 規則

- `CONFIRMED` 必須引用至少一筆 `G520_HARDWARE`、`EVIDENCE`、`PASS` 紀錄。
- `LIMITED` 必須引用至少一筆 `G520_HARDWARE`、`EVIDENCE`、`LIMITED` 紀錄，並在 assessment 寫清限制。
- `FAIL` 附在 `UNKNOWN` 列以保留失敗或阻塞事實，但不能自行變成支援證據。
- `MOCK` 與 `ANDROID_EMULATOR` 紀錄可證明各自 runtime 行為，永遠不能升級硬體列。
- 實際 evidence record 必須由至少一列引用；孤立紀錄會讓 validation fail closed。

## Matrix PR review checklist

- [ ] PR 說明列出被改動的 capability row 與 tracking issue。
- [ ] 附上第一手 `evidence://` raw log／artifact reference，且 reviewer 確實能透過受控 registry 讀取。
- [ ] commit、操作者、runtime profile、opaque device-set ID，以及受保護 evidence manifest 內的完整序號 tuple 都可供 reviewer 核對。
- [ ] Protected manifest 通過 JSON Schema，public/bundle binding 與 raw file SHA-256 已核對。
- [ ] 測試環境是 G520 + RC-N3 + Mini 4 Pro，而不是 mock、emulator 或 Pixel 8 Pro。
- [ ] 限制、失敗與觀測條件都保留，沒有只摘錄成功片段。
- [ ] `./gradlew :capability-matrix:test` 與 Markdown drift test 通過。
