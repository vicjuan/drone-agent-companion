# G520 點對點 Ethernet commissioning 準備

> 狀態：`UNVERIFIED_TEMPLATE`。本文件與相關工具是 commissioning 準備，沒有
> G520 第一手證據，不構成 issue #6 完成，也不得把 capability matrix 的
> `point_to_point_ethernet` 從 `UNKNOWN` 升級。

## 候選拓樸

唯一規劃中的拓樸是 Windows 筆電透過專用 USB Ethernet adapter 與 G520 直連：

```text
Windows operator                   G520 Android
10.52.0.1/30   <--- Ethernet --->  10.52.0.2/30:8080
```

候選網段是 `10.52.0.0/30`；network、兩個 usable host 與 broadcast 分別為
`.0`、`.1/.2`、`.3`。兩端都不得設定 gateway 或 DNS，Windows 必須關閉 DHCP、
Internet Connection Sharing、network bridge 與 IP forwarding。browser origin 必須逐字
等於 `http://10.52.0.2:8080`，不得有 path、trailing slash、hostname 或其他 port。

這些 IP 尚未由現場 route table 凍結。進真機 commissioning 前，人員必須先確認
Windows、VPN、G520 與其他受控網路均沒有 `10.52.0.0/30` 衝突；如需改值，必須一起
修改 canonical template、兩端設定與測試，而不是用 CLI 或 browser 臨時覆寫。

## Profile 與 adapter binding

Repo 內的
[`g520-point-to-point-v1.template.json`](../config/commissioning/g520-point-to-point-v1.template.json)
刻意是不可執行的 `UNVERIFIED_TEMPLATE`，adapter GUID 與 PnP identity 也刻意為
`null`。不要把 `scripts/commissioning/fixtures/profile.json` 用於現場；那只是測試資料。

現場人員應將 template 複製到受保護且不進版控的位置，完成以下步驟後才把狀態改成
`COMMISSIONING_CANDIDATE`：

1. 以完整 Windows adapter GUID 綁定唯一的實體 adapter；不得依顯示名稱或「第一張
   Ethernet」猜測。
2. 將 PnP device ID 正規化後只保存 SHA-256；公開輸出不得包含原始 PnP ID、MAC、
   MachineGuid 或序號。
3. `ifIndex` 保持 `null`。它會漂移，必須在每次 capture 時由 collector 另行觀察並與
   GUID 交叉核對。
4. 記錄候選 profile 檔案本身的 SHA-256；collector snapshot 必須綁定相同 hash。

## Windows 唯讀 preflight

先由管理員在 Windows PowerShell 7 中取得當次 `ifIndex`，再執行唯讀 collector：

```powershell
pwsh -NoProfile -File scripts/commissioning/collect-windows-point-to-point-snapshot.ps1 `
  -ProfilePath C:\protected\g520-p2p-candidate.json `
  -AdapterGuid 01234567-89ab-cdef-0123-456789abcdef `
  -IfIndex 17 `
  -G520IPv4 10.52.0.2 > C:\protected\g520-network-snapshot.json
```

在五分鐘內以 Node validator 驗證同一份 profile 與 snapshot：

```powershell
node scripts/commissioning/validate-windows-point-to-point-preflight.mjs `
  C:\protected\g520-p2p-candidate.json `
  C:\protected\g520-network-snapshot.json
```

validator 只有在完整且新鮮的 observed snapshot 同時證明以下條件時才回 exit `0`：

- profile 是受限的 RFC1918 `/30`、兩個不同 usable hosts、port 8080 與 exact Origin；
- 唯一 adapter 的 GUID、當次 ifIndex 與 PnP identity 全部吻合且狀態為 Up；
- 唯一 IPv4 是手動設定的 Windows endpoint，DHCP 關閉；
- route 僅有該 `/30` 與必要 endpoint/link-local/multicast 系統路由；
- `Find-NetRoute` 到 G520 選到同一 ifIndex 與 Windows source address；
- gateway、DNS、default/broad route、ICS、bridge 與 forwarding 全部不存在；
- 所有查詢都明確成功。權限不足、cmdlet/COM/netsh 讀取失敗、未知 enum、partial
  snapshot、舊 capture 或多重比對一律 fail closed。

Collector 不會修改 Windows 網路狀態；實際 fixed-IP provisioning 是獨立、人工受控的
步驟。其 stdout 是 bounded JSON，stderr 或 exception text 不得被當成 pass evidence。

## G520／Android 尚未接線的安全契約

目前 Android host 仍是 loopback-only mock profile，這個準備切片沒有改動 APK、network
security config 或 Ktor composition。未來實作必須遵守以下順序：

1. 使用 `ConnectivityManager`、`NetworkCapabilities.TRANSPORT_ETHERNET` 與
   `LinkProperties` 收集完整 inventory；query 或 permission 失敗即拒絕。
2. pure-JVM `commissioning-network` policy 只在唯一 Ethernet、exact G520 IP/prefix、
   exact `/30` route、無 DNS/gateway/其他 routable interface 時產生 binding。
3. local-IP bind 不等於 Linux `SO_BINDTODEVICE`。若 commissioning process 使用
   `bindProcessToNetwork`，仍須保存 Network/interface identity、listener table、OS
   firewall／其他介面關閉與負向不可達證據。
4. network ready 只能解除「網路 veto」，絕不能自行解鎖 DJI actuation。真致動仍需
   hardware commissioning mode、server-owned session 與 per-intent allowlist。
5. link、Network handle、interface name、IP、prefix、route 或 DNS 任一漂移時，callback
   邊界先同步關閉 terminal command admission；再由 lifecycle executor 執行 server
   stop → core close → fresh neutral/await → resource cleanup → durable evidence。舊 runtime
   generation 不得因網路 ABA 恢復而重開。

## 真板驗收與 evidence

真機前所有結果最高只有 `TESTED`。G520 到手後，受保護 evidence bundle至少保存：

- candidate commit、profile 原始 bytes/SHA-256、Windows collector/validator 版本與輸出；
- Windows OS/build、adapter GUID、當次 ifIndex、route/ICS/bridge/forwarding 原始結果；
- G520 build、boot ID、Ethernet Network/interface/LinkProperties、listener table與 route；
- 從目標 Windows path 的 SPA、exact-Origin WebSocket、telemetry、lease 與 10 Hz control
  soak；以及從 Wi-Fi／其他介面的負向不可達 probe；
- 拔線、IP/route drift、重插與 reboot 時的 admission closure、neutral、server stop及不會
  ABA 恢復舊 lease；
- 每個 raw file 的 hash、byte length、capture time與受控 registry reference。

只有 reviewer 能讀取上述第一手 bundle，且 issue #6 的所有條件實際通過後，才可考慮
更新 capability matrix。固定 IP、指定介面 bind、boot reachability、Windows preflight與
hardware evidence 在此之前全部保持未驗證。
