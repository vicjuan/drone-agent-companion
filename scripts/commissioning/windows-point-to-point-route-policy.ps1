Set-StrictMode -Version Latest

function Get-Slash30Endpoints {
    param([Parameter(Mandatory = $true)][System.Net.IPAddress] $Address)

    if ($Address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
        throw "Slash-30 endpoint calculation requires IPv4"
    }
    $networkBytes = [byte[]] $Address.GetAddressBytes().Clone()
    $networkBytes[3] = [byte] ($networkBytes[3] -band 0xfc)
    $broadcastBytes = [byte[]] $networkBytes.Clone()
    $broadcastBytes[3] = [byte] ($broadcastBytes[3] -bor 0x03)
    return [ordered]@{
        network = ([System.Net.IPAddress]::new($networkBytes)).ToString()
        broadcast = ([System.Net.IPAddress]::new($broadcastBytes)).ToString()
    }
}

function Test-CanonicalNetworkPrefix {
    param(
        [Parameter(Mandatory = $true)][System.Net.IPAddress] $Address,
        [Parameter(Mandatory = $true)][int] $PrefixLength
    )

    $maximumPrefixLength = 0
    if ($Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) {
        $maximumPrefixLength = 32
    }
    elseif ($Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetworkV6) {
        $maximumPrefixLength = 128
    }
    else {
        return $false
    }
    if ($PrefixLength -lt 0 -or $PrefixLength -gt $maximumPrefixLength) {
        return $false
    }

    $bytes = $Address.GetAddressBytes()
    $wholeBytes = [int] [Math]::Floor($PrefixLength / 8)
    $remainingBits = $PrefixLength % 8
    if ($remainingBits -gt 0) {
        $hostMask = 0xff -shr $remainingBits
        if (($bytes[$wholeBytes] -band $hostMask) -ne 0) {
            return $false
        }
    }
    $firstHostByte = $wholeBytes
    if ($remainingBits -gt 0) {
        $firstHostByte += 1
    }
    for ($index = $firstHostByte; $index -lt $bytes.Count; $index += 1) {
        if ($bytes[$index] -ne 0) {
            return $false
        }
    }
    return $true
}

function Test-AllowedPointToPointRoute {
    param(
        [Parameter(Mandatory = $true)][string] $DestinationPrefix,
        [Parameter(Mandatory = $true)][string] $ExpectedNetwork,
        [Parameter(Mandatory = $true)][string] $ExpectedBroadcast,
        [Parameter(Mandatory = $true)][string] $OperatorAddress,
        [Parameter(Mandatory = $true)][string] $G520Address
    )

    $parts = $DestinationPrefix.Split("/", 2)
    if ($parts.Count -ne 2) {
        return $false
    }
    $prefixLength = 0
    if (-not [int]::TryParse($parts[1], [ref] $prefixLength)) {
        return $false
    }
    $destination = $null
    if (-not [System.Net.IPAddress]::TryParse($parts[0], [ref] $destination)) {
        return $false
    }
    if (-not (Test-CanonicalNetworkPrefix -Address $destination -PrefixLength $prefixLength)) {
        return $false
    }
    $canonicalAddress = $destination.ToString()

    if (
        ($canonicalAddress -eq $ExpectedNetwork -and $prefixLength -eq 30) -or
        ($canonicalAddress -eq $ExpectedBroadcast -and $prefixLength -eq 32) -or
        ($canonicalAddress -eq $OperatorAddress -and $prefixLength -eq 32) -or
        ($canonicalAddress -eq $G520Address -and $prefixLength -eq 32)
    ) {
        return $true
    }

    $bytes = $destination.GetAddressBytes()
    if ($destination.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) {
        $isLinkLocal = $prefixLength -ge 16 -and $bytes[0] -eq 169 -and $bytes[1] -eq 254
        $isMulticast = $prefixLength -ge 4 -and ($bytes[0] -band 0xf0) -eq 0xe0
        $isLimitedBroadcast = $prefixLength -eq 32 -and $canonicalAddress -eq "255.255.255.255"
        return $isLinkLocal -or $isMulticast -or $isLimitedBroadcast
    }
    if ($destination.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetworkV6) {
        $isLinkLocal = $prefixLength -ge 10 -and $bytes[0] -eq 0xfe -and ($bytes[1] -band 0xc0) -eq 0x80
        $isMulticast = $prefixLength -ge 8 -and $bytes[0] -eq 0xff
        return $isLinkLocal -or $isMulticast
    }
    return $false
}
