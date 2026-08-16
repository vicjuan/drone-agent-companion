[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [ValidateNotNullOrEmpty()]
    [string] $MediaMtxExe
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$operatorAddress = "10.52.0.1"
$g520Address = "10.52.0.2"
$prefixLength = 30
$expectedNetwork = "10.52.0.0/30"
$firewallRuleName = "DroneAgentCompanion-G520-MediaMTX-Temporary"
$firewallRuleDisplayName = "Drone Agent Companion G520 MediaMTX (temporary)"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$configPath = Join-Path $repoRoot "config\media\mediamtx-windows-g520.yml"
$lockPath = Join-Path $repoRoot "config\media\mediamtx-v1.19.1-windows-amd64.lock.json"
$toolRoot = Join-Path $repoRoot ".drone-agent-companion\tools\mediamtx"

function Get-NormalizedSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string] $LiteralPath
    )

    return (Get-FileHash -LiteralPath $LiteralPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
}

function Assert-FileSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string] $LiteralPath,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedSha256,

        [Parameter(Mandatory = $true)]
        [string] $Label
    )

    if (-not (Test-Path -LiteralPath $LiteralPath -PathType Leaf)) {
        throw "$Label is missing: $LiteralPath"
    }
    $actualSha256 = Get-NormalizedSha256 -LiteralPath $LiteralPath
    if ($actualSha256 -cne $ExpectedSha256) {
        throw "$Label SHA-256 mismatch: expected $ExpectedSha256, got $actualSha256"
    }
}

function Read-PinnedReleaseLock {
    param(
        [Parameter(Mandatory = $true)]
        [string] $LiteralPath
    )

    if (-not (Test-Path -LiteralPath $LiteralPath -PathType Leaf)) {
        throw "MediaMTX release lock is missing: $LiteralPath"
    }
    $releaseLock = Get-Content -LiteralPath $LiteralPath -Raw -Encoding UTF8 -ErrorAction Stop |
        ConvertFrom-Json -ErrorAction Stop

    if ([int] $releaseLock.schemaVersion -ne 1) {
        throw "Unsupported MediaMTX release lock schema"
    }
    if ([string] $releaseLock.version -cne "1.19.1") {
        throw "MediaMTX release lock must remain pinned to v1.19.1"
    }

    $expectedArchiveName = "mediamtx_v1.19.1_windows_amd64.zip"
    $expectedOfficeConfigName = "mediamtx-windows-g520.yml"
    $expectedOfficeConfigSha256 = "a182e84f69215eb32804af0c1f11da9e960c748fb96effaa9c10c88cdbd0f02f"
    $expectedReleaseBase = "https://github.com/bluenviron/mediamtx/releases/download/v1.19.1"
    if ([string] $releaseLock.officeConfig.fileName -cne $expectedOfficeConfigName -or
        [string] $releaseLock.officeConfig.sha256 -cne $expectedOfficeConfigSha256 -or
        [string] $releaseLock.archive.fileName -cne $expectedArchiveName -or
        [string] $releaseLock.archive.url -cne "$expectedReleaseBase/$expectedArchiveName" -or
        [string] $releaseLock.officialChecksums.fileName -cne "checksums.sha256" -or
        [string] $releaseLock.officialChecksums.url -cne "$expectedReleaseBase/checksums.sha256" -or
        [string] $releaseLock.executable.fileName -cne "mediamtx.exe") {
        throw "MediaMTX release lock contains an unexpected asset or URL"
    }

    foreach ($sha256 in @(
        [string] $releaseLock.officeConfig.sha256,
        [string] $releaseLock.archive.sha256,
        [string] $releaseLock.officialChecksums.sha256,
        [string] $releaseLock.executable.sha256
    )) {
        if ($sha256 -cnotmatch "^[0-9a-f]{64}$") {
            throw "MediaMTX release lock contains an invalid SHA-256 value"
        }
    }

    return $releaseLock
}

function Invoke-PinnedDownload {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Uri,

        [Parameter(Mandatory = $true)]
        [string] $DestinationPath,

        [Parameter(Mandatory = $true)]
        [string] $ExpectedSha256,

        [Parameter(Mandatory = $true)]
        [string] $Label
    )

    if (Test-Path -LiteralPath $DestinationPath) {
        throw "Refusing to overwrite existing $Label cache: $DestinationPath"
    }

    $partialPath = "$DestinationPath.partial-$PID"
    if (Test-Path -LiteralPath $partialPath) {
        throw "Stale exact partial download path exists: $partialPath"
    }

    try {
        Write-Host "[g520-media] Downloading $Label from the pinned official release"
        [Net.ServicePointManager]::SecurityProtocol =
            [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $Uri -OutFile $partialPath -UseBasicParsing -TimeoutSec 120 -ErrorAction Stop
        Assert-FileSha256 -LiteralPath $partialPath -ExpectedSha256 $ExpectedSha256 -Label $Label
        Move-Item -LiteralPath $partialPath -Destination $DestinationPath -ErrorAction Stop
    }
    finally {
        if (Test-Path -LiteralPath $partialPath) {
            Remove-Item -LiteralPath $partialPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-VerifiedDefaultMediaMtxExe {
    param(
        [Parameter(Mandatory = $true)]
        [object] $ReleaseLock
    )

    $versionRoot = Join-Path $toolRoot "v1.19.1-windows-amd64"
    $downloadRoot = Join-Path $versionRoot "downloads"
    $archivePath = Join-Path $downloadRoot ([string] $ReleaseLock.archive.fileName)
    $officialChecksumsPath = Join-Path $downloadRoot "checksums.sha256"
    $executablePath = Join-Path $versionRoot "mediamtx.exe"

    New-Item -ItemType Directory -Path $downloadRoot -Force -ErrorAction Stop | Out-Null

    if (Test-Path -LiteralPath $executablePath -PathType Leaf) {
        Assert-FileSha256 `
            -LiteralPath $executablePath `
            -ExpectedSha256 ([string] $ReleaseLock.executable.sha256) `
            -Label "cached mediamtx.exe"
        return $executablePath
    }

    if (Test-Path -LiteralPath $archivePath -PathType Leaf) {
        Assert-FileSha256 `
            -LiteralPath $archivePath `
            -ExpectedSha256 ([string] $ReleaseLock.archive.sha256) `
            -Label "cached MediaMTX archive"
    }
    else {
        if (Test-Path -LiteralPath $officialChecksumsPath -PathType Leaf) {
            Assert-FileSha256 `
                -LiteralPath $officialChecksumsPath `
                -ExpectedSha256 ([string] $ReleaseLock.officialChecksums.sha256) `
                -Label "cached official checksums.sha256"
        }
        else {
            Invoke-PinnedDownload `
                -Uri ([string] $ReleaseLock.officialChecksums.url) `
                -DestinationPath $officialChecksumsPath `
                -ExpectedSha256 ([string] $ReleaseLock.officialChecksums.sha256) `
                -Label "official checksums.sha256"
        }

        $expectedChecksumLine = "$([string] $ReleaseLock.archive.sha256) *$([string] $ReleaseLock.archive.fileName)"
        $archiveChecksumLines = @(
            Get-Content -LiteralPath $officialChecksumsPath -Encoding UTF8 -ErrorAction Stop |
                Where-Object { $_ -match ([regex]::Escape([string] $ReleaseLock.archive.fileName) + "$") }
        )
        if ($archiveChecksumLines.Count -ne 1 -or $archiveChecksumLines[0] -cne $expectedChecksumLine) {
            throw "Pinned archive digest does not match the verified official checksums.sha256 entry"
        }

        Invoke-PinnedDownload `
            -Uri ([string] $ReleaseLock.archive.url) `
            -DestinationPath $archivePath `
            -ExpectedSha256 ([string] $ReleaseLock.archive.sha256) `
            -Label "MediaMTX v1.19.1 Windows amd64 archive"
    }

    $stagingRoot = Join-Path $versionRoot ".extract-$PID"
    if (Test-Path -LiteralPath $stagingRoot) {
        throw "Stale exact extraction path exists: $stagingRoot"
    }

    try {
        New-Item -ItemType Directory -Path $stagingRoot -ErrorAction Stop | Out-Null
        Expand-Archive -LiteralPath $archivePath -DestinationPath $stagingRoot -ErrorAction Stop
        $stagedExecutable = Join-Path $stagingRoot ([string] $ReleaseLock.executable.fileName)
        Assert-FileSha256 `
            -LiteralPath $stagedExecutable `
            -ExpectedSha256 ([string] $ReleaseLock.executable.sha256) `
            -Label "extracted mediamtx.exe"
        Move-Item -LiteralPath $stagedExecutable -Destination $executablePath -ErrorAction Stop
    }
    finally {
        if (Test-Path -LiteralPath $stagingRoot) {
            Remove-Item -LiteralPath $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    Assert-FileSha256 `
        -LiteralPath $executablePath `
        -ExpectedSha256 ([string] $ReleaseLock.executable.sha256) `
        -Label "installed mediamtx.exe"
    return $executablePath
}

function Assert-PointToPointEthernet {
    $addressRows = @(
        Get-NetIPAddress `
            -AddressFamily IPv4 `
            -PolicyStore ActiveStore `
            -IPAddress $operatorAddress `
            -ErrorAction Stop
    )
    if ($addressRows.Count -ne 1) {
        throw "Windows must expose exactly one active $operatorAddress IPv4 address"
    }

    $addressRow = $addressRows[0]
    if ([int] $addressRow.PrefixLength -ne $prefixLength -or
        [string] $addressRow.PrefixOrigin -ne "Manual" -or
        [string] $addressRow.SuffixOrigin -ne "Manual" -or
        [string] $addressRow.AddressState -ne "Preferred") {
        throw "Windows Ethernet must have preferred, manually configured $operatorAddress/$prefixLength"
    }

    $interfaceIndex = [int] $addressRow.InterfaceIndex
    $adapters = @(
        Get-NetAdapter -IncludeHidden -ErrorAction Stop |
            Where-Object {
                $candidateIndex = $_.PSObject.Properties["InterfaceIndex"]
                if ($null -eq $candidateIndex) {
                    $candidateIndex = $_.PSObject.Properties["ifIndex"]
                }
                $null -ne $candidateIndex -and [int] $candidateIndex.Value -eq $interfaceIndex
            }
    )
    if ($adapters.Count -ne 1 -or [string] $adapters[0].Status -ne "Up") {
        throw "The exact $operatorAddress/$prefixLength Ethernet adapter must be uniquely present and Up"
    }
    $hardwareInterface = $adapters[0].PSObject.Properties["HardwareInterface"]
    $mediaTypes = @(
        [string] $adapters[0].PSObject.Properties["MediaType"].Value,
        [string] $adapters[0].PSObject.Properties["PhysicalMediaType"].Value
    )
    if ($null -eq $hardwareInterface -or -not [bool] $hardwareInterface.Value -or
        @($mediaTypes | Where-Object { $_ -ceq "802.3" }).Count -eq 0) {
        throw "The selected interface must be a physical 802.3 Ethernet adapter"
    }

    $interfaceAddresses = @(
        Get-NetIPAddress `
            -InterfaceIndex $interfaceIndex `
            -AddressFamily IPv4 `
            -PolicyStore ActiveStore `
            -ErrorAction Stop
    )
    if ($interfaceAddresses.Count -ne 1 -or
        [string] $interfaceAddresses[0].IPAddress -cne $operatorAddress -or
        [int] $interfaceAddresses[0].PrefixLength -ne $prefixLength) {
        throw "The selected Ethernet adapter must have only $operatorAddress/$prefixLength as active IPv4"
    }

    $ipInterfaces = @(
        Get-NetIPInterface `
            -InterfaceIndex $interfaceIndex `
            -AddressFamily IPv4 `
            -PolicyStore ActiveStore `
            -ErrorAction Stop
    )
    if ($ipInterfaces.Count -ne 1 -or
        [string] $ipInterfaces[0].Dhcp -ne "Disabled" -or
        [string] $ipInterfaces[0].Forwarding -ne "Disabled") {
        throw "DHCP and IP forwarding must be disabled on the selected Ethernet adapter"
    }

    $forwardingRows = @(Get-NetIPInterface -PolicyStore ActiveStore -ErrorAction Stop)
    if ($forwardingRows.Count -eq 0 -or
        @($forwardingRows | Where-Object { [string] $_.Forwarding -ne "Disabled" }).Count -ne 0) {
        throw "Windows IP forwarding must be disabled on every active-store interface"
    }

    $routes = @(
        Get-NetRoute `
            -InterfaceIndex $interfaceIndex `
            -AddressFamily IPv4 `
            -PolicyStore ActiveStore `
            -ErrorAction Stop
    )
    $connectedRoutes = @(
        $routes | Where-Object {
            [string] $_.DestinationPrefix -ceq $expectedNetwork -and
            [string] $_.NextHop -ceq "0.0.0.0"
        }
    )
    $gatewayRoutes = @(
        $routes | Where-Object {
            $nextHop = [string] $_.NextHop
            -not [string]::IsNullOrWhiteSpace($nextHop) -and $nextHop -cne "0.0.0.0"
        }
    )
    if ($connectedRoutes.Count -ne 1 -or $gatewayRoutes.Count -ne 0) {
        throw "The selected Ethernet adapter must have one $expectedNetwork connected route and no gateway"
    }

    $dnsRows = @(
        Get-DnsClientServerAddress `
            -InterfaceIndex $interfaceIndex `
            -AddressFamily IPv4 `
            -ErrorAction Stop
    )
    $dnsServerCount = 0
    foreach ($dnsRow in $dnsRows) {
        $dnsServerCount += @($dnsRow.ServerAddresses).Count
    }
    if ($dnsRows.Count -eq 0 -or $dnsServerCount -ne 0) {
        throw "DNS must be explicitly absent on the selected Ethernet adapter"
    }

    $routeSelection = @(Find-NetRoute -RemoteIPAddress $g520Address -ErrorAction Stop)
    $sourceAddressRows = @(
        $routeSelection | Where-Object { $null -ne $_.PSObject.Properties["IPAddress"] }
    )
    $routeRows = @(
        $routeSelection | Where-Object { $null -ne $_.PSObject.Properties["DestinationPrefix"] }
    )
    if ($sourceAddressRows.Count -ne 1 -or $routeRows.Count -ne 1 -or
        [int] $sourceAddressRows[0].InterfaceIndex -ne $interfaceIndex -or
        [string] $sourceAddressRows[0].IPAddress -cne $operatorAddress -or
        [int] $routeRows[0].InterfaceIndex -ne $interfaceIndex) {
        throw "Windows does not route $g520Address through the exact $operatorAddress source/interface"
    }

    return $interfaceIndex
}

function Assert-NoConnectionSharingOrBridge {
    $bridgeBindings = @(
        Get-NetAdapterBinding `
            -AllBindings `
            -ComponentID "ms_bridge" `
            -ErrorAction Stop |
            Where-Object { [bool] $_.Enabled }
    )
    if ($bridgeBindings.Count -ne 0) {
        $bridgeNames = @($bridgeBindings | ForEach-Object { [string] $_.Name }) -join ", "
        throw "Windows Network Bridge is enabled on: $bridgeNames"
    }

    $sharingManager = New-Object -ComObject HNetCfg.HNetShare -ErrorAction Stop
    $connections = @($sharingManager.EnumEveryConnection)
    foreach ($connection in $connections) {
        $sharingConfiguration =
            $sharingManager.INetSharingConfigurationForINetConnection($connection)
        if ($null -eq $sharingConfiguration) {
            throw "Unable to read one Windows connection-sharing configuration"
        }
        if ([bool] $sharingConfiguration.SharingEnabled) {
            $properties = $sharingManager.NetConnectionProps($connection)
            $connectionName =
                if ($null -ne $properties -and
                    -not [string]::IsNullOrWhiteSpace([string] $properties.Name)) {
                    [string] $properties.Name
                }
                else {
                    "<unnamed>"
                }
            throw "Internet Connection Sharing is enabled on $connectionName"
        }
    }
}

if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
    throw "This commissioning launcher only runs on Windows"
}
if (-not [System.Environment]::Is64BitOperatingSystem) {
    throw "MediaMTX windows_amd64 requires 64-bit Windows"
}
$windowsPrincipal = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent()
)
if (-not $windowsPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this commissioning launcher from an elevated Administrator PowerShell"
}
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "MediaMTX office config not found: $configPath"
}

$releaseLock = Read-PinnedReleaseLock -LiteralPath $lockPath
Assert-FileSha256 `
    -LiteralPath $configPath `
    -ExpectedSha256 ([string] $releaseLock.officeConfig.sha256) `
    -Label "MediaMTX office config"
$resolvedExecutable = $null
if ([string]::IsNullOrWhiteSpace($MediaMtxExe)) {
    $resolvedExecutable = Get-VerifiedDefaultMediaMtxExe -ReleaseLock $releaseLock
}
else {
    if (-not (Test-Path -LiteralPath $MediaMtxExe -PathType Leaf)) {
        throw "Requested MediaMTX executable not found: $MediaMtxExe"
    }
    $resolvedExecutable = (Resolve-Path -LiteralPath $MediaMtxExe).Path
    Assert-FileSha256 `
        -LiteralPath $resolvedExecutable `
        -ExpectedSha256 ([string] $releaseLock.executable.sha256) `
        -Label "requested mediamtx.exe"
}

$interfaceIndex = Assert-PointToPointEthernet
Assert-NoConnectionSharingOrBridge
$existingFirewallRules = @(
    Get-NetFirewallRule -Name $firewallRuleName -ErrorAction SilentlyContinue
)
if ($existingFirewallRules.Count -ne 0) {
    throw "Refusing to alter existing firewall rule named $firewallRuleName"
}

$firewallRuleCreated = $false
try {
    New-NetFirewallRule `
        -Name $firewallRuleName `
        -DisplayName $firewallRuleDisplayName `
        -Description "Temporary G520 RTMP ingest rule; owned by start-windows-g520-media.ps1" `
        -Enabled True `
        -Profile Any `
        -Direction Inbound `
        -Action Allow `
        -Program $resolvedExecutable `
        -Protocol TCP `
        -LocalAddress $operatorAddress `
        -LocalPort 1935 `
        -RemoteAddress $g520Address `
        -RemotePort Any `
        -InterfaceType Wired `
        -EdgeTraversalPolicy Block `
        -ErrorAction Stop | Out-Null
    $firewallRuleCreated = $true

    Write-Host "[g520-media] MediaMTX v1.19.1 Windows amd64 verified"
    Write-Host "[g520-media] Ethernet:    $operatorAddress/$prefixLength (ifIndex $interfaceIndex) -> $g520Address"
    Write-Host "[g520-media] Firewall:    temporary $firewallRuleName"
    Write-Host "[g520-media] RTMP ingest: rtmp://$operatorAddress`:1935/dji-main"
    Write-Host "[g520-media] WHEP page:   http://$operatorAddress`:8891/dji-main"
    Write-Host "[g520-media] Foreground process started; press Ctrl+C to stop"

    & $resolvedExecutable $configPath
    $mediaMtxExitCode = $LASTEXITCODE
}
finally {
    if ($firewallRuleCreated) {
        Remove-NetFirewallRule -Name $firewallRuleName -ErrorAction Stop
        Write-Host "[g520-media] Removed temporary firewall rule $firewallRuleName"
    }
}

exit $mediaMtxExitCode
