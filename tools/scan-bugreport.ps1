# Bouwt BtSnoopScan en laat het los op een Android-bugrapport of een los btsnoop-bestand.
#
#   .\tools\scan-bugreport.ps1 -Path "$HOME\Downloads\bugreport-xxx.zip"
#   .\tools\scan-bugreport.ps1 -Path ... -All        # inclusief de telemetrieherhalingen
#
# Zonder -Path pakt hij het nieuwste bugreport-zip uit je Downloads.
param(
    [string]$Path,
    [switch]$All
)
$ErrorActionPreference = "Stop"

if (-not $Path) {
    $guess = Get-ChildItem "$HOME\Downloads" -Filter "*bugreport*.zip" -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $guess) { throw "geen bugreport-zip gevonden in $HOME\Downloads; geef -Path op" }
    $Path = $guess.FullName
    Write-Host "gevonden: $Path"
}
if (-not (Test-Path $Path)) { throw "bestand niet gevonden: $Path" }

# Korte werkmap: csc struikelt over lange uitvoerpaden.
$build = Join-Path $env:TEMP "b04clab"
New-Item -ItemType Directory -Force -Path $build | Out-Null
Copy-Item (Join-Path $PSScriptRoot "BtSnoopScan.cs") (Join-Path $build "BtSnoopScan.cs") -Force

$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$fw  = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319"
& $csc /nologo /target:exe /out:"$build\BtSnoopScan.exe" `
    /r:"$fw\System.IO.Compression.dll" `
    /r:"$fw\System.IO.Compression.FileSystem.dll" `
    "$build\BtSnoopScan.cs"
if ($LASTEXITCODE -ne 0) { throw "compileren mislukt" }

$out = Join-Path $build "btsnoop-frames.txt"
if ($All) { & "$build\BtSnoopScan.exe" $Path --all | Tee-Object -FilePath $out }
else      { & "$build\BtSnoopScan.exe" $Path        | Tee-Object -FilePath $out }
Write-Host ""
Write-Host "volledige uitvoer: $out"
