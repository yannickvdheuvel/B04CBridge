# Bouwt en start de B04C-labbridge: praat rechtstreeks vanaf Windows met het display,
# zodat protocolvragen te beantwoorden zijn zonder de APK-cyclus via GitHub Actions.
#
# Gebruik:
#   .\tools\build-and-run.ps1                       # bouwen en 60 minuten verbonden blijven
#   .\tools\build-and-run.ps1 -Minutes 15
#   .\tools\build-and-run.ps1 -BuildOnly
#
# Terwijl hij draait stuur je commando's door een regel in cmd.txt te zetten, bijvoorbeeld
# vanuit een tweede venster:
#   "nav 350 2 1200 3 0 1 5000" > $env:TEMP\b04clab\cmd.txt
#
# Commando's: nav <bochtM> <code> <volgM> <code> <derdeM> <code> <totaalM>
#             frame <targetHex> <subHex> <paramHex> [payloadbytes hex...]
#             raw <bytes hex...>
#             stopnav
#             sweep <vanCode> <totCode> [ms]
#             quit
param(
    [string]$Addr    = "70DEF9D3A09E",   # BLE-adres van het B04C-BF
    [int]   $Minutes = 60,
    [switch]$BuildOnly
)
$ErrorActionPreference = "Stop"

# Bewust een korte werkmap: csc.exe faalt met "Kan tijdelijk bestand niet maken" zodra het
# uitvoerpad tegen MAX_PATH aan loopt, en dat gebeurt in een diepe projectmap zo gebeurd.
$build = Join-Path $env:TEMP "b04clab"
New-Item -ItemType Directory -Force -Path $build | Out-Null
Copy-Item (Join-Path $PSScriptRoot "B04CLab.cs") (Join-Path $build "B04CLab.cs") -Force

$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$fw  = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319"
$wm  = "C:\Windows\System32\WinMetadata"

if (-not (Test-Path $csc)) { throw "csc.exe niet gevonden; .NET Framework 4.x ontbreekt" }
if (-not (Test-Path $wm))  { throw "$wm ontbreekt; zonder WinRT-metadata is er geen BLE" }

# Geen System.Runtime.WindowsRuntime erbij: die facade is gebouwd tegen de union-Windows.winmd
# uit de Windows SDK. Staat die niet op de machine, dan botsen er twee IBuffer-identiteiten en
# compileert er niets meer. De losse winmd's uit System32 zijn genoeg.
& $csc /nologo /nowarn:0414 /target:exe /out:"$build\B04CLab.exe" `
    /r:"$fw\System.Runtime.dll" `
    /r:"$fw\System.Runtime.InteropServices.WindowsRuntime.dll" `
    /r:"$wm\Windows.Foundation.winmd" `
    /r:"$wm\Windows.Devices.winmd" `
    /r:"$wm\Windows.Storage.winmd" `
    "$build\B04CLab.cs"
if ($LASTEXITCODE -ne 0) { throw "compileren mislukt" }
Write-Host "gebouwd: $build\B04CLab.exe"
if ($BuildOnly) { return }

Set-Content -Path "$build\cmd.txt" -Value "" -Encoding ascii
Write-Host "commando's -> $build\cmd.txt"
Write-Host "log        -> $build\lab.log"
& "$build\B04CLab.exe" $Addr "$build\cmd.txt" "$build\lab.log" $Minutes
