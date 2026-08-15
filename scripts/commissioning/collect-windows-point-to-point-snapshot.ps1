[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $ProfilePath,

    [Parameter(Mandatory = $true)]
    [Guid] $AdapterGuid,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int] $IfIndex,

    [Parameter(Mandatory = $true)]
    [System.Net.IPAddress] $G520IPv4
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

. (Join-Path $PSScriptRoot "windows-point-to-point-route-policy.ps1")

function ConvertTo-CanonicalGuid {
    param([AllowNull()][object] $Value)

    if ($null -eq $Value) {
        return $null
    }
    try {
        return ([Guid] $Value).ToString("D").ToLowerInvariant()
    }
    catch {
        return $null
    }
}

function Get-StringSha256 {
    param([Parameter(Mandatory = $true)][string] $Value)

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $hashBytes = $sha256.ComputeHash($bytes)
        return ([System.BitConverter]::ToString($hashBytes)).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }
}

function Get-AdapterIfIndex {
    param([Parameter(Mandatory = $true)][object] $Adapter)

    foreach ($propertyName in @("InterfaceIndex", "ifIndex")) {
        $property = $Adapter.PSObject.Properties[$propertyName]
        if ($null -ne $property -and $null -ne $property.Value) {
            return [int] $property.Value
        }
    }
    throw "Adapter interface index is unavailable"
}

function New-QueryResult {
    return [ordered]@{ complete = $false; status = "NOT_RUN" }
}

function Set-QueryResult {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][ValidateSet("OK", "ERROR", "NOT_RUN")][string] $Status
    )

    $queries[$Name].status = $Status
    $queries[$Name].complete = $Status -eq "OK"
}

$requestedGuid = $AdapterGuid.ToString("D").ToLowerInvariant()
$g520Address = $G520IPv4.ToString()
$g520AddressIsIpv4 = $G520IPv4.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork
$queries = [ordered]@{
    profileHash = New-QueryResult
    hostIdentity = New-QueryResult
    adapter = New-QueryResult
    ipv4 = New-QueryResult
    dhcp = New-QueryResult
    gateway = New-QueryResult
    dns = New-QueryResult
    defaultRoute = New-QueryResult
    routeToG520 = New-QueryResult
    ics = New-QueryResult
    bridge = New-QueryResult
    forwarding = New-QueryResult
}
$adapterObservation = [ordered]@{
    exactMatchCount = 0
    partialMatchCount = 0
    adapterGuid = $null
    pnpDeviceIdSha256 = $null
    ifIndex = $null
    operationalStatus = $null
}
$ipv4Observation = [ordered]@{
    addressCount = 0
    address = $null
    prefixLength = $null
    prefixOrigin = $null
    suffixOrigin = $null
    addressState = $null
}
$dhcpObservation = [ordered]@{ state = $null }
$gatewayObservation = [ordered]@{ count = 0 }
$dnsObservation = [ordered]@{ serverCount = 0 }
$defaultRouteObservation = [ordered]@{
    count = 0
    expectedConnectedRouteCount = 0
    unexpectedRouteCount = 0
}
$routeToG520Observation = [ordered]@{
    sourceAddressResultCount = 0
    routeResultCount = 0
    targetAddress = $g520Address
    sourceInterfaceIndex = $null
    routeInterfaceIndex = $null
    sourceAddress = $null
}
$icsObservation = [ordered]@{
    enabledConnectionCount = 0
    selectedAdapterEnabled = $null
}
$bridgeObservation = [ordered]@{
    adapterCount = 0
    enabledMemberCount = 0
    selectedAdapterMember = $null
}
$forwardingObservation = [ordered]@{
    enabledInterfaceCount = 0
    unknownStateCount = 0
    selectedAdapterEnabled = $null
}
$profileSha256 = $null
$hostIdentitySha256 = $null

try {
    $profileSha256 = (Get-FileHash -LiteralPath $ProfilePath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
    Set-QueryResult -Name "profileHash" -Status "OK"
}
catch {
    Set-QueryResult -Name "profileHash" -Status "ERROR"
}

try {
    $machineGuid = [string] (Get-ItemPropertyValue -LiteralPath "HKLM:\SOFTWARE\Microsoft\Cryptography" -Name "MachineGuid" -ErrorAction Stop)
    if ([string]::IsNullOrWhiteSpace($machineGuid)) {
        throw "Host identity is unavailable"
    }
    $hostIdentitySha256 = Get-StringSha256 ($machineGuid.Trim().ToLowerInvariant())
    Set-QueryResult -Name "hostIdentity" -Status "OK"
}
catch {
    Set-QueryResult -Name "hostIdentity" -Status "ERROR"
}

$allAdapters = @()
$selectedAdapter = $null
try {
    $allAdapters = @(Get-NetAdapter -IncludeHidden -ErrorAction Stop)
    $exactMatches = @(
        $allAdapters | Where-Object {
            (ConvertTo-CanonicalGuid $_.InterfaceGuid) -eq $requestedGuid -and
            (Get-AdapterIfIndex $_) -eq $IfIndex
        }
    )
    $partialMatches = @(
        $allAdapters | Where-Object {
            $guidMatches = (ConvertTo-CanonicalGuid $_.InterfaceGuid) -eq $requestedGuid
            $indexMatches = (Get-AdapterIfIndex $_) -eq $IfIndex
            ($guidMatches -or $indexMatches) -and -not ($guidMatches -and $indexMatches)
        }
    )
    $adapterObservation.exactMatchCount = $exactMatches.Count
    $adapterObservation.partialMatchCount = $partialMatches.Count

    $candidate = $null
    if ($exactMatches.Count -eq 1) {
        $candidate = $exactMatches[0]
        $selectedAdapter = $candidate
    }
    elseif ($exactMatches.Count -eq 0 -and $partialMatches.Count -eq 1) {
        $candidate = $partialMatches[0]
    }
    if ($null -ne $candidate) {
        $adapterObservation.adapterGuid = ConvertTo-CanonicalGuid $candidate.InterfaceGuid
        $candidateIfIndex = Get-AdapterIfIndex $candidate
        $adapterObservation.ifIndex = $candidateIfIndex
        $adapterObservation.operationalStatus = [string] $candidate.Status
        $pnpProperty = $candidate.PSObject.Properties["PnPDeviceID"]
        $pnpDeviceId = $null
        if ($null -ne $pnpProperty) {
            $pnpDeviceId = [string] $pnpProperty.Value
        }
        if ([string]::IsNullOrWhiteSpace($pnpDeviceId)) {
            $candidateCimRows = @(
                Get-CimInstance -ClassName Win32_NetworkAdapter -ErrorAction Stop |
                    Where-Object { [int] $_.InterfaceIndex -eq $candidateIfIndex }
            )
            if ($candidateCimRows.Count -ne 1) {
                throw "Selected adapter PNP identity is unavailable"
            }
            $pnpDeviceId = [string] $candidateCimRows[0].PNPDeviceID
        }
        if ([string]::IsNullOrWhiteSpace($pnpDeviceId)) {
            throw "Selected adapter PNP identity is unavailable"
        }
        $adapterObservation.pnpDeviceIdSha256 = Get-StringSha256 ($pnpDeviceId.Trim().ToUpperInvariant())
    }
    Set-QueryResult -Name "adapter" -Status "OK"
}
catch {
    Set-QueryResult -Name "adapter" -Status "ERROR"
}

if ($null -ne $selectedAdapter) {
    try {
        $ipv4Rows = @(
            Get-NetIPAddress -InterfaceIndex $IfIndex -AddressFamily IPv4 -PolicyStore ActiveStore -ErrorAction Stop |
                Sort-Object -Property IPAddress, PrefixLength
        )
        $ipv4Observation.addressCount = $ipv4Rows.Count
        if ($ipv4Rows.Count -eq 1) {
            $ipv4Observation.address = [string] $ipv4Rows[0].IPAddress
            $ipv4Observation.prefixLength = [int] $ipv4Rows[0].PrefixLength
            $ipv4Observation.prefixOrigin = [string] $ipv4Rows[0].PrefixOrigin
            $ipv4Observation.suffixOrigin = [string] $ipv4Rows[0].SuffixOrigin
            $ipv4Observation.addressState = [string] $ipv4Rows[0].AddressState
        }
        Set-QueryResult -Name "ipv4" -Status "OK"
    }
    catch {
        Set-QueryResult -Name "ipv4" -Status "ERROR"
    }

    try {
        $ipInterfaceRows = @(
            Get-NetIPInterface -InterfaceIndex $IfIndex -AddressFamily IPv4 -PolicyStore ActiveStore -ErrorAction Stop
        )
        if ($ipInterfaceRows.Count -ne 1) {
            throw "Unexpected selected IPv4 interface cardinality"
        }
        $dhcpObservation.state = [string] $ipInterfaceRows[0].Dhcp
        Set-QueryResult -Name "dhcp" -Status "OK"
    }
    catch {
        Set-QueryResult -Name "dhcp" -Status "ERROR"
    }

    try {
        $selectedRoutes = @(Get-NetRoute -InterfaceIndex $IfIndex -PolicyStore ActiveStore -ErrorAction Stop)
        $gatewayObservation.count = @(
            $selectedRoutes | Where-Object {
                $nextHop = [string] $_.NextHop
                -not [string]::IsNullOrWhiteSpace($nextHop) -and
                $nextHop -ne "0.0.0.0" -and
                $nextHop -ne "::"
            }
        ).Count
        Set-QueryResult -Name "gateway" -Status "OK"
    }
    catch {
        Set-QueryResult -Name "gateway" -Status "ERROR"
    }

    try {
        $dnsRows = @(Get-DnsClientServerAddress -InterfaceIndex $IfIndex -ErrorAction Stop)
        if ($dnsRows.Count -eq 0) {
            throw "Selected DNS client state is unavailable"
        }
        $dnsCount = 0
        foreach ($dnsRow in $dnsRows) {
            $dnsCount += @($dnsRow.ServerAddresses).Count
        }
        $dnsObservation.serverCount = $dnsCount
        Set-QueryResult -Name "dns" -Status "OK"
    }
    catch {
        Set-QueryResult -Name "dns" -Status "ERROR"
    }

    try {
        if ($ipv4Observation.addressCount -ne 1 -or $ipv4Observation.prefixLength -ne 30) {
            throw "Expected point-to-point IPv4 state is unavailable"
        }
        $slash30 = Get-Slash30Endpoints -Address $G520IPv4
        $selectedRoutes = @(Get-NetRoute -InterfaceIndex $IfIndex -PolicyStore ActiveStore -ErrorAction Stop)
        $defaultRoutes = @(
            $selectedRoutes | Where-Object {
                $destination = [string] $_.DestinationPrefix
                $destination -eq "0.0.0.0/0" -or $destination -eq "::/0"
            }
        )
        $expectedConnectedRoutes = @(
            $selectedRoutes | Where-Object {
                [string] $_.DestinationPrefix -eq "$($slash30.network)/30"
            }
        )
        $unexpectedRoutes = @(
            $selectedRoutes | Where-Object {
                -not (Test-AllowedPointToPointRoute `
                    -DestinationPrefix ([string] $_.DestinationPrefix) `
                    -ExpectedNetwork $slash30.network `
                    -ExpectedBroadcast $slash30.broadcast `
                    -OperatorAddress ([string] $ipv4Observation.address) `
                    -G520Address $g520Address)
            }
        )
        $defaultRouteObservation.count = $defaultRoutes.Count
        $defaultRouteObservation.expectedConnectedRouteCount = $expectedConnectedRoutes.Count
        $defaultRouteObservation.unexpectedRouteCount = $unexpectedRoutes.Count
        Set-QueryResult -Name "defaultRoute" -Status "OK"
    }
    catch {
        Set-QueryResult -Name "defaultRoute" -Status "ERROR"
    }

    try {
        if (-not $g520AddressIsIpv4) {
            throw "G520 route target is not IPv4"
        }
        $routeSelection = @(Find-NetRoute -RemoteIPAddress $g520Address -ErrorAction Stop)
        $sourceAddressRows = @(
            $routeSelection | Where-Object { $null -ne $_.PSObject.Properties["IPAddress"] }
        )
        $routeRows = @(
            $routeSelection | Where-Object { $null -ne $_.PSObject.Properties["DestinationPrefix"] }
        )
        $routeToG520Observation.sourceAddressResultCount = $sourceAddressRows.Count
        $routeToG520Observation.routeResultCount = $routeRows.Count
        if ($sourceAddressRows.Count -eq 1) {
            $routeToG520Observation.sourceInterfaceIndex = [int] $sourceAddressRows[0].InterfaceIndex
            $routeToG520Observation.sourceAddress = [string] $sourceAddressRows[0].IPAddress
        }
        if ($routeRows.Count -eq 1) {
            $routeToG520Observation.routeInterfaceIndex = [int] $routeRows[0].InterfaceIndex
        }
        Set-QueryResult -Name "routeToG520" -Status "OK"
    }
    catch {
        Set-QueryResult -Name "routeToG520" -Status "ERROR"
    }
}

try {
    $sharingManager = New-Object -ComObject "HNetCfg.HNetShare.1" -ErrorAction Stop
    try {
        $enabledSharingCount = 0
        $selectedSharingEnabled = $false
        $selectedSharingConnectionObserved = $false
        $connections = @($sharingManager.EnumEveryConnection())
        foreach ($connection in $connections) {
            $properties = $sharingManager.NetConnectionProps($connection)
            $configuration = $sharingManager.INetSharingConfigurationForINetConnection($connection)
            if ((ConvertTo-CanonicalGuid $properties.Guid) -eq $requestedGuid) {
                $selectedSharingConnectionObserved = $true
                $selectedSharingEnabled = [bool] $configuration.SharingEnabled
            }
            if ([bool] $configuration.SharingEnabled) {
                $enabledSharingCount += 1
            }
        }
        if ($null -ne $selectedAdapter -and -not $selectedSharingConnectionObserved) {
            throw "Selected Internet Connection Sharing state is unavailable"
        }
        $icsObservation.enabledConnectionCount = $enabledSharingCount
        $icsObservation.selectedAdapterEnabled = $selectedSharingEnabled
        Set-QueryResult -Name "ics" -Status "OK"
    }
    finally {
        if ($null -ne $sharingManager -and [System.Runtime.InteropServices.Marshal]::IsComObject($sharingManager)) {
            [void] [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($sharingManager)
        }
    }
}
catch {
    Set-QueryResult -Name "ics" -Status "ERROR"
}

try {
    if ($null -eq $selectedAdapter) {
        Set-QueryResult -Name "bridge" -Status "NOT_RUN"
    }
    else {
        $cimAdapters = @(Get-CimInstance -ClassName Win32_NetworkAdapter -ErrorAction Stop)
        $bridgeObservation.adapterCount = @(
            $cimAdapters | Where-Object { [string] $_.ServiceName -ieq "BridgeMP" }
        ).Count

        $bridgeBindings = @(
            Get-NetAdapterBinding -ComponentID "ms_bridge" -AllBindings -IncludeHidden -ErrorAction Stop
        )
        $bridgeObservation.enabledMemberCount = @(
            $bridgeBindings | Where-Object { [bool] $_.Enabled }
        ).Count

        $sameDescriptionAdapters = @(
            $allAdapters | Where-Object { [string] $_.InterfaceDescription -eq [string] $selectedAdapter.InterfaceDescription }
        )
        if ($sameDescriptionAdapters.Count -ne 1) {
            throw "Selected adapter binding association is ambiguous"
        }
        $selectedBindings = @(
            $bridgeBindings | Where-Object {
                [string] $_.InterfaceDescription -eq [string] $selectedAdapter.InterfaceDescription
            }
        )
        $bridgeObservation.selectedAdapterMember = @(
            $selectedBindings | Where-Object { [bool] $_.Enabled }
        ).Count -gt 0
        $null = & netsh.exe bridge show adapter 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Network bridge state is unreadable"
        }
        Set-QueryResult -Name "bridge" -Status "OK"
    }
}
catch {
    Set-QueryResult -Name "bridge" -Status "ERROR"
}

try {
    if ($null -eq $selectedAdapter) {
        Set-QueryResult -Name "forwarding" -Status "NOT_RUN"
    }
    else {
        $forwardingRows = @(Get-NetIPInterface -PolicyStore ActiveStore -ErrorAction Stop)
        $enabledCount = 0
        $unknownCount = 0
        $selectedEnabled = $false
        $selectedForwardingRowCount = 0
        foreach ($row in $forwardingRows) {
            $forwardingState = [string] $row.Forwarding
            if ($forwardingState -eq "Enabled") {
                $enabledCount += 1
                if ([int] $row.InterfaceIndex -eq $IfIndex) {
                    $selectedForwardingRowCount += 1
                    $selectedEnabled = $true
                }
            }
            elseif ($forwardingState -ne "Disabled") {
                $unknownCount += 1
            }
            elseif ([int] $row.InterfaceIndex -eq $IfIndex) {
                $selectedForwardingRowCount += 1
            }
        }
        if ($selectedForwardingRowCount -eq 0) {
            throw "Selected IP forwarding state is unavailable"
        }
        $forwardingObservation.enabledInterfaceCount = $enabledCount
        $forwardingObservation.unknownStateCount = $unknownCount
        $forwardingObservation.selectedAdapterEnabled = $selectedEnabled
        Set-QueryResult -Name "forwarding" -Status "OK"
    }
}
catch {
    Set-QueryResult -Name "forwarding" -Status "ERROR"
}

$allQueriesSucceeded = @($queries.Values | Where-Object { -not $_.complete -or $_.status -ne "OK" }).Count -eq 0
$captureStatus = "PARTIAL"
if ($allQueriesSucceeded) {
    $captureStatus = "COMPLETE"
}

$snapshot = [ordered]@{
    schemaVersion = 1
    captureStatus = $captureStatus
    collector = [ordered]@{
        name = "windows-point-to-point-readonly"
        version = "1.0.0"
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString(
            "yyyy-MM-dd'T'HH:mm:ss.fff'Z'",
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        hostIdentitySha256 = $hostIdentitySha256
        profileSha256 = $profileSha256
    }
    selector = [ordered]@{
        adapterGuid = $requestedGuid
        ifIndex = $IfIndex
    }
    queries = $queries
    adapter = $adapterObservation
    ipv4 = $ipv4Observation
    dhcp = $dhcpObservation
    gateway = $gatewayObservation
    dns = $dnsObservation
    defaultRoute = $defaultRouteObservation
    routeToG520 = $routeToG520Observation
    ics = $icsObservation
    bridge = $bridgeObservation
    forwarding = $forwardingObservation
}

$snapshot | ConvertTo-Json -Depth 5 -Compress
