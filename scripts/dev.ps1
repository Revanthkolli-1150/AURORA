# Windows PowerShell local development launcher for AURORA
Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "⚡ AURORA Reliability Platform - Developer Launcher" -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

if (-not (Test-Path .env)) {
    Write-Host "Creating .env from .env.example..." -ForegroundColor Yellow
    Copy-Item .env.example .env
}

Write-Host "Running npm install..." -ForegroundColor Green
cmd /c "npm install"

Write-Host "Starting AURORA Console..." -ForegroundColor Green
cmd /c "npm run dev:console"
