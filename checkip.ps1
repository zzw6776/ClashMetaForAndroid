param(
    [switch]$NoPause
)

$ErrorActionPreference = "Stop"

try {
    chcp 65001 > $null
} catch {
}

[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

Set-Location -LiteralPath $PSScriptRoot

$bashCommand = Get-Command bash -ErrorAction SilentlyContinue
$bash = if ($bashCommand) { $bashCommand.Source } else { $null }

if (-not $bash) {
    $gitBash = "C:\Program Files\Git\bin\bash.exe"
    if (Test-Path -LiteralPath $gitBash) {
        $bash = $gitBash
    }
}

if (-not $bash) {
    Write-Host "未找到 bash。请安装 Git for Windows，或把 Git Bash 加入 PATH。" -ForegroundColor Red
    if (-not $NoPause) {
        Read-Host "按回车退出"
    }
    exit 1
}

function Convert-ToBashPath {
    param([string]$Path)

    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction SilentlyContinue
    $fullPath = if ($resolved) { $resolved.Path } else { $Path }

    if ($fullPath -match "^([A-Za-z]):\\(.*)$") {
        $drive = $Matches[1].ToLowerInvariant()
        $rest = $Matches[2] -replace "\\", "/"
        return "/$drive/$rest"
    }

    return $fullPath -replace "\\", "/"
}

function Test-PythonCommand {
    param(
        [string]$Exe,
        [string[]]$Args = @()
    )

    if (-not $Exe) {
        return $false
    }

    & $Exe @Args --version > $null 2>&1
    return $LASTEXITCODE -eq 0
}

$pythonExe = $null
$pythonArgs = ""
foreach ($candidate in @("python3", "python")) {
    $command = Get-Command $candidate -ErrorAction SilentlyContinue
    if (-not $command -or -not $command.Source) {
        continue
    }

    if (Test-PythonCommand -Exe $command.Source) {
        $pythonExe = $command.Source
        break
    }
}

if (-not $pythonExe) {
    $pyCommand = Get-Command py -ErrorAction SilentlyContinue
    if ($pyCommand -and $pyCommand.Source) {
        if (Test-PythonCommand -Exe $pyCommand.Source -Args @("-3")) {
            $pythonExe = $pyCommand.Source
            $pythonArgs = "-3"
        }
    }
}

if (-not $pythonExe) {
    $pythonSearchRoots = @(
        (Join-Path $env:USERPROFILE ".cache\codex-runtimes"),
        (Join-Path $env:LOCALAPPDATA "Programs\Python"),
        $env:ProgramFiles
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    foreach ($root in $pythonSearchRoots) {
        $candidateExe = Get-ChildItem -LiteralPath $root -Filter python.exe -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notmatch "\\WindowsApps\\" } |
            Select-Object -First 1

        if ($candidateExe -and (Test-PythonCommand -Exe $candidateExe.FullName)) {
            $pythonExe = $candidateExe.FullName
            break
        }
    }
}

if (-not $pythonExe) {
    Write-Host "未找到 Python。请安装 Python，或把 python/python3/py 加入 PATH。" -ForegroundColor Red
    if (-not $NoPause) {
        Read-Host "按回车退出"
    }
    exit 1
}

$env:CHECKIP_PYTHON_EXE = Convert-ToBashPath $pythonExe
$env:CHECKIP_PYTHON_ARGS = $pythonArgs
& $bash -c 'python3() { "$CHECKIP_PYTHON_EXE" ${CHECKIP_PYTHON_ARGS:-} "$@"; }; source ./checkip.sh'
$exitCode = $LASTEXITCODE
$env:CHECKIP_PYTHON_EXE = $null
$env:CHECKIP_PYTHON_ARGS = $null

if (-not $NoPause) {
    Write-Host ""
    Read-Host "按回车退出"
}

exit $exitCode
