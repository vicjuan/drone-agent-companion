package com.durendal.droneagent.companion.capability

object CapabilityMatrixMarkdownRenderer {
    fun render(document: G520CapabilityMatrixDocument): String {
        CapabilityMatrixValidator.validate(document)
        return buildString {
            appendLine("<!-- GENERATED from config/capability-matrix/g520-stack.json; DO NOT EDIT BY HAND. -->")
            appendLine("# G520 Stack Capability Matrix")
            appendLine()
            appendLine("這是 Mini 4 Pro + RC-N3 + G520（Android）stack 的唯一硬體證據矩陣。")
            appendLine("Pixel 8 Pro 或其他 stack 的證據不得轉移；mock、emulator、編譯與單元測試也不得升級硬體狀態。")
            appendLine()
            appendLine("- Matrix ID：`${escapeInlineCode(document.matrixId)}`")
            appendLine("- Aircraft：${escapeCell(document.targetStack.aircraft)}")
            appendLine("- Remote controller：${escapeCell(document.targetStack.remoteController)}")
            appendLine("- Host：${escapeCell(document.targetStack.host)}")
            appendLine("- Last updated：`${document.lastUpdated}`")
            appendLine("- Machine source：[`config/capability-matrix/g520-stack.json`](../config/capability-matrix/g520-stack.json)")
            appendLine()
            appendLine("## Capability evidence")
            appendLine()
            appendLine("狀態語彙只有 `CONFIRMED`、`LIMITED`、`UNKNOWN`。失敗結果附在 `UNKNOWN` 列；不能用 mock／emulator 成功取代第一手 G520 證據。")
            appendLine()
            appendLine("| ID | Capability | Status | Current assessment | Verification method | Tracking | Evidence |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- |")
            document.rows.forEach { row ->
                appendLine(
                    "| `${escapeInlineCode(row.id)}` | ${escapeCell(row.title)} | `${row.status}` | " +
                        "${escapeCell(row.assessment)} | ${escapeCell(row.verificationMethod)} | " +
                        "${row.trackingIssues.joinToString(", ") { "#$it" }} | " +
                        "${row.evidenceRefs.takeIf { it.isNotEmpty() }?.joinToString(", ") { "`${escapeInlineCode(it)}`" } ?: "—"} |",
                )
            }
            appendLine()
            appendLine("## Evidence records")
            appendLine()
            if (document.evidenceRecords.isEmpty()) {
                appendLine("目前沒有可升級狀態的第一手 G520 evidence record。格式與三種環境的明確範例見 [`docs/evidence/README.md`](evidence/README.md)。")
            } else {
                appendLine("| ID | Environment | Captured at | Commit | Operator | Runtime profile | Device set | Tracking | Outcome | Summary | Raw evidence |")
                appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
                document.evidenceRecords.forEach { record ->
                    appendLine(
                        "| `${escapeInlineCode(record.id)}` | `${record.environment}` | `${record.capturedAt}` | " +
                            "`${record.commit}` | ${escapeCell(record.operator)} | ${escapeCell(record.runtimeProfile)} | " +
                            "${record.deviceSetId?.let { "`${escapeInlineCode(it)}`" } ?: "—"} | #${record.trackingIssue} | " +
                            "`${record.outcome}` | ${escapeCell(record.summary)} | `${escapeInlineCode(record.rawLogLocation)}` |",
                    )
                }
            }
            appendLine()
            appendLine("## Runtime state is separate")
            appendLine()
            appendLine("Web Console 的 adapter、aircraft connection、actuation lock 與 control lease 是即時 runtime state；它們不得覆寫或推導本矩陣的 evidence status。")
        }
    }

    internal fun escapeCell(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r\n", "<br>")
            .replace("\n", "<br>")
            .replace("\r", "<br>")

    private fun escapeInlineCode(value: String): String = value.replace("`", "\\`")
}
