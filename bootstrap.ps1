# CardLife Launcher - bootstrap
# 1) finds JDK 17+ (installed or previously downloaded into .tools\jdk-17), otherwise downloads Temurin 17
# 2) downloads FlatLaf into lib\ (optional - the launcher also works without it)
# Every download: TLS 1.2, 3 retries per mirror, several mirrors, 3 transfer methods
# (Invoke-WebRequest -> curl.exe -> BITS), size + hash + archive validation.
# Result: .tools\java_home.txt contains the JDK folder. Exit code 0 = OK, 2 = JDK unavailable.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # progress bar makes Invoke-WebRequest 10x slower in Windows PowerShell

$Root = $PSScriptRoot
$Tools = Join-Path $Root '.tools'
$Lib = Join-Path $Root 'lib'
$FlatLafVersion = '3.6.1'

# Old Windows PowerShell defaults to TLS 1.0/1.1, which GitHub/Adoptium/Maven Central reject.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072 } catch {}
try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 12288 } catch {}
try { Add-Type -AssemblyName System.IO.Compression.FileSystem } catch {}

# ----------------------------------------------------------------------------- helpers

function Test-ZipFile([string]$Path) {
    try {
        $z = [IO.Compression.ZipFile]::OpenRead($Path)
        try { return ($z.Entries.Count -gt 0) } finally { $z.Dispose() }
    } catch { return $false }
}

function Test-JdkZip([string]$Path) {
    try {
        $z = [IO.Compression.ZipFile]::OpenRead($Path)
        try {
            foreach ($e in $z.Entries) { if ($e.FullName -like '*bin/javac.exe') { return $true } }
            return $false
        } finally { $z.Dispose() }
    } catch { return $false }
}

function Get-TextFromUrl([string]$Url) {
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 30
        $c = $r.Content
        if ($c -is [byte[]]) { $c = [Text.Encoding]::ASCII.GetString($c) }
        return ([string]$c).Trim()
    } catch { return $null }
}

# Downloads the first working URL into $Dest. Returns $true / $false.
function Save-Url {
    param(
        [string[]]$Urls,
        [string]$Dest,
        [long]$MinBytes = 1024,
        [string]$Hash,
        [string]$HashAlg = 'SHA256',
        [string]$HashUrl,
        [scriptblock]$Check
    )
    $tmp = $Dest + '.part'
    foreach ($u in $Urls) {
        for ($i = 1; $i -le 3; $i++) {
            Write-Host ("  download [{0}/3] {1}" -f $i, $u)
            if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
            $done = $false

            # method 1: Invoke-WebRequest
            try {
                Invoke-WebRequest -Uri $u -OutFile $tmp -UseBasicParsing -TimeoutSec 900 -UserAgent 'Mozilla/5.0 (CardLifeLauncher)'
                $done = $true
            } catch { Write-Host ("    web request failed: " + $_.Exception.Message) }

            # method 2: curl.exe (ships with Windows 10 1803+)
            if (-not $done) {
                $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
                if ($curl) {
                    try {
                        & $curl.Source -L --fail --silent --show-error --connect-timeout 20 --retry 2 -o $tmp $u 2>$null
                        if ($LASTEXITCODE -eq 0) { $done = $true } else { Write-Host ("    curl exit code " + $LASTEXITCODE) }
                    } catch { Write-Host ("    curl failed: " + $_.Exception.Message) }
                }
            }

            # method 3: BITS
            if (-not $done) {
                try {
                    Start-BitsTransfer -Source $u -Destination $tmp -ErrorAction Stop | Out-Null
                    $done = $true
                } catch { Write-Host ("    BITS failed: " + $_.Exception.Message) }
            }

            if ($done -and (Test-Path -LiteralPath $tmp)) {
                $good = ((Get-Item -LiteralPath $tmp).Length -ge $MinBytes)
                if (-not $good) { Write-Host '    file is too small (probably an error page)' }
                if ($good -and $Hash -and ((-not $HashUrl) -or ($HashUrl -eq $u))) {
                    $actual = (Get-FileHash -LiteralPath $tmp -Algorithm $HashAlg).Hash
                    if ($actual.ToLower() -ne $Hash.ToLower()) {
                        Write-Host ("    {0} mismatch (expected {1}, got {2})" -f $HashAlg, $Hash, $actual)
                        $good = $false
                    } else { Write-Host ("    {0} OK" -f $HashAlg) }
                }
                if ($good -and $Check) {
                    try { $good = [bool](& $Check $tmp) } catch { $good = $false }
                    if (-not $good) { Write-Host '    archive validation failed' }
                }
                if ($good) {
                    Move-Item -LiteralPath $tmp -Destination $Dest -Force
                    return $true
                }
            }
            Start-Sleep -Seconds (2 * $i)
        }
    }
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
    return $false
}

# ----------------------------------------------------------------------------- JDK discovery

function Get-JdkMajor([string]$JdkHome) {
    if (-not $JdkHome) { return 0 }
    if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\java.exe') -PathType Leaf)) { return 0 }
    if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\javac.exe') -PathType Leaf)) { return 0 }
    # The 'release' file exists in every modern JDK - no need to start java.exe
    $rel = Join-Path $JdkHome 'release'
    if (Test-Path -LiteralPath $rel -PathType Leaf) {
        $m = Select-String -LiteralPath $rel -Pattern '^JAVA_VERSION="?(\d+)(?:\.(\d+))?' | Select-Object -First 1
        if ($m) {
            $maj = [int]$m.Matches[0].Groups[1].Value
            if ($maj -eq 1 -and $m.Matches[0].Groups[2].Success) { $maj = [int]$m.Matches[0].Groups[2].Value }
            return $maj
        }
    }
    try {
        $exe = Join-Path $JdkHome 'bin\java.exe'
        $out = (cmd.exe /c ('"' + $exe + '" -version 2>&1') | Out-String)
        if ($out -match 'version "(?:1\.)?(\d+)') { return [int]$Matches[1] }
    } catch {}
    return 0
}

$script:cands = New-Object System.Collections.Generic.List[string]
function Add-Cand([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $Path = [Environment]::ExpandEnvironmentVariables($Path.Trim().Trim('"'))
    if (Test-Path -LiteralPath $Path -PathType Container) { $script:cands.Add($Path) }
}

function Find-Jdk {
    Add-Cand (Join-Path $Tools 'jdk-17')
    if ($env:JAVA_HOME) { Add-Cand $env:JAVA_HOME }
    try {
        $w = & where.exe java 2>$null
        foreach ($x in @($w)) { if ($x) { Add-Cand (Split-Path (Split-Path $x -Parent) -Parent) } }
    } catch {}

    $roots = @(
        "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Amazon Corretto", "$env:ProgramFiles\BellSoft", "$env:ProgramFiles\Zulu",
        "$env:ProgramFiles\Semeru", "$env:ProgramFiles\OpenJDK", "${env:ProgramFiles(x86)}\Java",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium", "$env:USERPROFILE\.jdks"
    )
    foreach ($root in $roots) {
        if (-not $root -or -not (Test-Path -LiteralPath $root)) { continue }
        foreach ($d1 in @(Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue)) {
            Add-Cand $d1.FullName
            foreach ($d2 in @(Get-ChildItem -LiteralPath $d1.FullName -Directory -ErrorAction SilentlyContinue)) { Add-Cand $d2.FullName }
        }
    }

    $regKeys = @('HKLM:\SOFTWARE\JavaSoft\JDK', 'HKLM:\SOFTWARE\JavaSoft\Java Development Kit',
                 'HKLM:\SOFTWARE\Eclipse Adoptium\JDK', 'HKLM:\SOFTWARE\WOW6432Node\JavaSoft\JDK')
    foreach ($k in $regKeys) {
        try {
            if (Test-Path $k) {
                foreach ($sub in @(Get-ChildItem $k -ErrorAction SilentlyContinue)) {
                    $jh = (Get-ItemProperty -Path $sub.PSPath -ErrorAction SilentlyContinue).JavaHome
                    if ($jh) { Add-Cand $jh }
                }
            }
        } catch {}
    }

    $best = $null; $bestMajor = 0
    foreach ($c in ($script:cands | Select-Object -Unique)) {
        $m = Get-JdkMajor $c
        if ($m -eq 17) { return $c }                       # exactly 17 - perfect
        if ($m -gt 17 -and ($bestMajor -eq 0 -or $m -lt $bestMajor)) { $best = $c; $bestMajor = $m }
    }
    return $best                                            # otherwise the lowest JDK newer than 17
}

# ----------------------------------------------------------------------------- JDK download

function Install-Jdk17 {
    New-Item -ItemType Directory -Path $Tools -Force | Out-Null
    $zip = Join-Path $env:TEMP 'cardlife-jdk17.zip'
    $urls = New-Object System.Collections.Generic.List[string]
    $sha = $null; $shaUrl = $null

    # Primary: exact package link + SHA-256 from the Adoptium API
    try {
        $api = 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
        $assets = Invoke-RestMethod -Uri $api -TimeoutSec 60
        foreach ($a in @($assets)) {
            $pk = $a.binary.package
            if ($pk -and $pk.link -and ($pk.link -like '*.zip')) {
                $urls.Add([string]$pk.link); $sha = [string]$pk.checksum; $shaUrl = [string]$pk.link
                break
            }
        }
    } catch { Write-Host ("  Adoptium API unavailable: " + $_.Exception.Message) }

    # Fallbacks (different vendors / hosts)
    $urls.Add('https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse')
    $urls.Add('https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip')
    $urls.Add('https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip')

    Write-Host 'JDK 17 not found - downloading a portable copy (about 180 MB)...'
    $ok = @(Save-Url -Urls $urls.ToArray() -Dest $zip -MinBytes 100000000 -Hash $sha -HashAlg 'SHA256' -HashUrl $shaUrl -Check { param($f) Test-JdkZip $f })[-1]
    if (-not $ok) { throw 'all JDK download sources failed' }

    Write-Host 'Extracting JDK...'
    $extract = Join-Path $Tools 'jdk17-extract'
    if (Test-Path -LiteralPath $extract) { Remove-Item -LiteralPath $extract -Recurse -Force }
    [IO.Compression.ZipFile]::ExtractToDirectory($zip, $extract)

    $javac = Get-ChildItem -LiteralPath $extract -Filter javac.exe -Recurse -File | Select-Object -First 1
    if (-not $javac) { throw 'javac.exe not found inside the downloaded archive' }
    $src = Split-Path (Split-Path $javac.FullName -Parent) -Parent
    if ((Get-JdkMajor $src) -lt 17) { throw 'downloaded JDK is older than 17' }

    $dst = Join-Path $Tools 'jdk-17'
    if (Test-Path -LiteralPath $dst) { Remove-Item -LiteralPath $dst -Recurse -Force }
    Move-Item -LiteralPath $src -Destination $dst -Force
    Remove-Item -LiteralPath $extract -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    return $dst
}

# ----------------------------------------------------------------------------- FlatLaf

function Ensure-FlatLaf {
    $name = "flatlaf-$FlatLafVersion.jar"
    $jar = Join-Path $Lib $name
    if ((Test-Path -LiteralPath $jar) -and (Test-ZipFile $jar)) { Write-Host "[OK] FlatLaf $FlatLafVersion present"; return }
    New-Item -ItemType Directory -Path $Lib -Force | Out-Null

    $rel = "com/formdev/flatlaf/$FlatLafVersion/$name"
    $mirrors = @(
        'https://repo1.maven.org/maven2/',
        'https://repo.maven.apache.org/maven2/',
        'https://maven-central.storage-download.googleapis.com/maven2/',
        'https://maven.aliyun.com/repository/central/'
    )
    $urls = @($mirrors | ForEach-Object { $_ + $rel })

    $sha1 = $null
    foreach ($m in $mirrors) {
        $t = Get-TextFromUrl ($m + $rel + '.sha1')
        if ($t) {
            $cand = $t.Split(' ')[0].Trim().ToLower()
            if ($cand -match '^[0-9a-f]{40}$') { $sha1 = $cand; break }
        }
    }

    Write-Host "Downloading FlatLaf $FlatLafVersion..."
    $ok = @(Save-Url -Urls $urls -Dest $jar -MinBytes 200000 -Hash $sha1 -HashAlg 'SHA1' -Check { param($f) Test-ZipFile $f })[-1]
    if ($ok) { Write-Host "[OK] FlatLaf downloaded" }
    else { Write-Host '[WARN] FlatLaf could not be downloaded - the launcher will use the standard Java look and feel.' }
}

# ----------------------------------------------------------------------------- main

Write-Host '=== CardLife Launcher: checking JDK and libraries ==='
New-Item -ItemType Directory -Path $Tools -Force | Out-Null

$jdk = $null
try { $jdk = Find-Jdk } catch { Write-Host ('JDK search error: ' + $_.Exception.Message) }

if (-not $jdk) {
    try { $jdk = Install-Jdk17 }
    catch {
        Write-Host ''
        Write-Host ('[ERROR] Could not download JDK 17: ' + $_.Exception.Message)
        Write-Host 'Check the internet connection / antivirus / proxy, or install JDK 17 manually:'
        Write-Host '  https://adoptium.net/temurin/releases/?version=17'
        exit 2
    }
}

$major = Get-JdkMajor $jdk
Write-Host ("[OK] JDK {0}: {1}" -f $major, $jdk)
[IO.File]::WriteAllText((Join-Path $Tools 'java_home.txt'), $jdk, (New-Object Text.UTF8Encoding($false)))

try { Ensure-FlatLaf } catch { Write-Host ('[WARN] FlatLaf step failed: ' + $_.Exception.Message) }
exit 0
