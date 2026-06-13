$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$chroma = Join-Path $root '.venv-chroma\Scripts\chroma.exe'
$data = Join-Path $root 'runtime-data\chroma'

if (-not (Test-Path $chroma)) {
    throw 'Chroma is not installed. Run scripts\setup-chroma.ps1 first.'
}

New-Item -ItemType Directory -Force -Path $data | Out-Null
& $chroma run --path $data --host 127.0.0.1 --port 8000
