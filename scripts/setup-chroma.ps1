$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$venv = Join-Path $root '.venv-chroma'

try {
    & py -3.12 --version | Out-Host
} catch {
    throw 'Python 3.12 was not found. Install it with: winget install --id Python.Python.3.12 --exact'
}

if (-not (Test-Path $venv)) {
    & py -3.12 -m venv $venv
}

$python = Join-Path $venv 'Scripts\python.exe'
& $python -m pip install --upgrade pip
& $python -m pip install -r (Join-Path $root 'requirements-chroma.txt')

Write-Host 'Chroma environment is ready. Run scripts\start-chroma.ps1 to start it.'
