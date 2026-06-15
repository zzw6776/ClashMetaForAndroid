param(
    [string]$IP
)

$configFile = Join-Path $PSScriptRoot ".adb_config"
$defaultIP = "192.168.50.114"
$lastPort = ""

# 1. Load configuration
if (Test-Path $configFile) {
    $config = Get-Content $configFile | ConvertFrom-Json
    if ($null -ne $config.IP) { $defaultIP = $config.IP }
    if ($null -ne $config.Port) { $lastPort = $config.Port }
}

if ([string]::IsNullOrEmpty($IP)) {
    $IP = $defaultIP
}

Write-Host ">>> Preparing connection to device: $IP" -ForegroundColor Cyan

# 2. Try to connect using the last successful port
if (-not [string]::IsNullOrEmpty($lastPort)) {
    Write-Host "Trying last successful port: $lastPort ..."
    $target = "${IP}:${lastPort}"
    $result = adb connect $target
    if ($result -match "connected to" -or $result -match "already connected to") {
        Write-Host "Success (reused last port)!" -ForegroundColor Green
        # Save config
        @{ IP = $IP; Port = $lastPort } | ConvertTo-Json | Out-File $configFile -Encoding utf8
        adb devices
        exit
    }
}

# 3. If direct connection fails, scan ports (35000-50000)
$ports = 35000..50000
$timeout = 150 # ms
$openPorts = [System.Collections.Generic.List[int]]::new()
$batchSize = 1000
$totalPorts = $ports.Count

Write-Host "Direct connection failed. Scanning ADB ports on $IP (35000-50000)..." -ForegroundColor Yellow

for ($i = 0; $i -lt $totalPorts; $i += $batchSize) {
    $endIdx = [Math]::Min($i + $batchSize - 1, $totalPorts - 1)
    $batch = $ports[$i..$endIdx]
    $results = [System.Collections.Generic.List[PSCustomObject]]::new()
    
    foreach ($port in $batch) {
        if ($port -eq $null) { continue }
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $ar = $client.BeginConnect($IP, $port, $null, $null)
            $results.Add([PSCustomObject]@{ Port = $port; Client = $client; AR = $ar })
        } catch {
            $client.Close()
        }
    }
    
    [System.Threading.Thread]::Sleep($timeout)
    
    foreach ($r in $results) {
        if ($r.AR.IsCompleted) {
            try {
                $r.Client.EndConnect($r.AR)
                $openPorts.Add($r.Port)
            } catch {}
        }
        $r.Client.Close()
    }
}

# 4. Try to connect to scanned open ports
$connected = $false
foreach ($port in $openPorts) {
    Write-Host "Trying port: $port ..."
    $target = "${IP}:${port}"
    $result = adb connect $target
    if ($result -match "connected to" -or $result -match "already connected to") {
        Write-Host "Success! Connected to port: $port" -ForegroundColor Green
        @{ IP = $IP; Port = [string]$port } | ConvertTo-Json | Out-File $configFile -Encoding utf8
        $connected = $true
        break
    }
}

if (-not $connected) {
    Write-Host "Failed to connect. Please make sure wireless debugging is enabled and IP $IP is correct." -ForegroundColor Red
} else {
    adb devices
}
