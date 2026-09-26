param(
    [ValidateSet('setup', 'start', 'stop', 'status', 'test')]
    [string]$Action = 'status',
    [string]$Python = '',
    [string]$MySqlBin = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$venvPython = Join-Path $repoRoot '.local/venv/Scripts/python.exe'
if ($Action -eq 'setup') {
    if (-not (Test-Path -LiteralPath $venvPython)) {
        if (-not $Python) {
            $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
            if ($pythonCommand) { $Python = $pythonCommand.Source }
        }
        if (-not $Python) { throw 'Pass -Python with a Python 3.12+ executable path.' }
        & $Python -m venv (Join-Path $repoRoot '.local/venv')
        if ($LASTEXITCODE -ne 0) { throw 'Virtual environment creation failed.' }
    }
    & $venvPython -m pip install -r (Join-Path $PSScriptRoot 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
}
if (-not (Test-Path -LiteralPath $venvPython)) { throw 'Run setup first.' }
$devArguments = @((Join-Path $PSScriptRoot 'dev.py'), $Action)
if ($MySqlBin) { $devArguments += @('--mysql-bin', $MySqlBin) }
& $venvPython @devArguments
if ($LASTEXITCODE -ne 0) { throw "Local environment command failed: $Action" }
