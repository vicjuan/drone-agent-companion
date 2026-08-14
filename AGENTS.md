# AGENTS.md

Guidance for AI agents (and humans) working in this repository.

## 開工前必讀

1. [`HANDOFF.md`](HANDOFF.md) — 任務背景、現況盤點、不可跨越的邊界。
2. [`docs/architecture.md`](docs/architecture.md) — 系統架構與安全邊界。
3. [`docs/implementation-order.md`](docs/implementation-order.md) — 撰寫順序與完成判準。

## 繼承的強制規範

本 repo 的工程紀律**完全繼承** `drone-agent-android` 的 `AGENTS.md`，包括：

- **AI Agent 完成聲明與驗證守則**：八級驗證狀態不得跳級、證據不足時 fail
  closed、每次收尾的強制完成回報格式。
- **Android 昂貴驗證規則**：frozen candidate gate、驗證失效範圍、每個 candidate
  每個昂貴 lane 最多執行一次。

來源：`vendor/drone-agent-android/AGENTS.md`（S0 建立 submodule 後即存在），
或 https://github.com/vicjuan/drone-agent-android/blob/main/AGENTS.md

該文件與本文件衝突時，以該文件為準；本文件只補充本 repo 特有的邊界。

## 本 repo 特有的邊界

- `drone-agent-android` 是**唯讀** submodule，不在其中修改或 commit。
- `contracts/agent-protocol/` 是跨 repo 凍結契約，不得為 UI 需求更動。
- 命令一律走 `CommandAdmissionPolicy`，不得有繞過的端點。
- 致動類命令在 server 與 UI 兩層都必須 fail-closed。
- 目標 stack（Mini 4 Pro + RC-N3 + G520）的 capability matrix 全列 `UNKNOWN`
  起跳；`drone-agent-android` 在 Pixel 8 Pro stack 的 `CONFIRMED` **不得轉移**。

詳細理由見 `HANDOFF.md` 第 4 節。
