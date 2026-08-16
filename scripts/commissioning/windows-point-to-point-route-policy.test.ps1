Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "windows-point-to-point-route-policy.ps1")

$commonArguments = @{
    ExpectedNetwork = "10.52.0.0"
    ExpectedBroadcast = "10.52.0.3"
    OperatorAddress = "10.52.0.1"
    G520Address = "10.52.0.2"
}

function Assert-RouteDecision {
    param(
        [Parameter(Mandatory = $true)][string] $DestinationPrefix,
        [Parameter(Mandatory = $true)][bool] $Expected
    )

    $actual = Test-AllowedPointToPointRoute -DestinationPrefix $DestinationPrefix @commonArguments
    if ($actual -ne $Expected) {
        throw "Unexpected route-policy decision for bounded test case: $DestinationPrefix"
    }
}

@(
    "10.52.0.0/30",
    "10.52.0.1/32",
    "10.52.0.2/32",
    "10.52.0.3/32",
    "169.254.0.0/16",
    "169.254.10.0/24",
    "224.0.0.0/4",
    "239.255.255.250/32",
    "fe80::/10",
    "fe80:1::/32",
    "ff00::/8",
    "ff02::/16"
) | ForEach-Object { Assert-RouteDecision -DestinationPrefix $_ -Expected $true }

@(
    "10.52.0.0/29",
    "169.254.0.0/0",
    "169.254.10.1/24",
    "224.0.0.0/0",
    "fe80::/0",
    "fe80::1/64",
    "ff00::/0",
    "192.168.0.0/16",
    "0.0.0.0/0",
    "::/0"
) | ForEach-Object { Assert-RouteDecision -DestinationPrefix $_ -Expected $false }

"PowerShell route policy tests PASS"
