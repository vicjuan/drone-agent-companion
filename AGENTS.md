# AGENTS.md

Guidance for AI agents (and humans) working in this repository.

## 開工前必讀

1. [`docs/architecture.md`](docs/architecture.md) — 系統架構與安全邊界。
2. [`docs/implementation-order.md`](docs/implementation-order.md) — 撰寫順序與完成判準。

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
- 致動類命令可在 `adapter-mock` 下端到端執行；真機預設 fail-closed，只能在明確的
  hardware commissioning session 中逐項開放。瀏覽器斷線、控制租約失效或命令逾時
  時必須回到 neutral。
- 目標 stack（Mini 4 Pro + RC-N3 + G520）的 capability matrix 全列 `UNKNOWN`
  起跳；`drone-agent-android` 在 Pixel 8 Pro stack 的 `CONFIRMED` **不得轉移**。

## Weekend Web Control MVP 邊界

- 週末目標是以 `adapter-mock` 跑通可見、可操作的完整 Web 控制面，包括 takeoff、
  landing、RTH、上升／下降、前進／後退、左旋／右旋。
- 開發 runner 只綁 `127.0.0.1`。G520 commissioning 則只綁點對點 Ethernet 介面；
  Windows 不得開啟 Internet Connection Sharing 或 network bridge。
- 帳號、密碼、TLS 與 credential lifecycle 不在週末 MVP；在擴大到任何共享或可路由
  網路前必須完成。
- UI 不需要固定的「MOCK DEMO」橫幅，但必須如實顯示 adapter、aircraft connection
  與 actuation lock 狀態，不得把 mock runtime 說成真機證據。

## 環境

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
```

本機預設 JDK 是 25，會讓 Gradle 8.7 失敗，每個新 shell 都要先設定。這是最常見
的假故障來源。

## 本階段的驗證天花板

G520 尚未到手，所有工作只能以 `adapter-mock` 與 Android emulator 驗證。可達到
`RUNTIME_VERIFIED`（Mac 或 emulator 環境），**不可能達到 `HARDWARE_VERIFIED`**。

mock 行為、可編譯、單元測試通過、issue 關閉，一律不構成硬體證據，不得用來把
capability matrix 的任何一列從 `UNKNOWN` 升級。emulator 通過不代表 G520 通過。
