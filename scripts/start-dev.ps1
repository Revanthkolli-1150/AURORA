# ==============================================================================
# AURORA Reliability Platform - Development Environment Launcher
# ==============================================================================
# Verifies prerequisites, loads .env, launches PostgreSQL in Docker,
# waits for healthcheck, and starts the AURORA Control Plane.
# ==============================================================================

[CmdletBinding()]
param (
    [switch]$DatabaseOnly,
    [switch]$Restart
)

$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$ControlPlaneDir = Join-Path $ProjectRoot "platform\aurora-control-plane"

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "AURORA Platform - Starting Local Development Environment" -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "Project Root: $ProjectRoot" -ForegroundColor DarkGray

# 1. Verify Prerequisites
Write-Host "`n[1/5] Checking prerequisites..." -ForegroundColor Yellow

$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Write-Host "[ERROR] Java is not installed or not in PATH. Java 21 LTS is required." -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] Java runtime found ($($javaCmd.Source))" -ForegroundColor Green

$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
if (-not $dockerCmd) {
    Write-Host "[ERROR] Docker CLI is not installed or not in PATH." -ForegroundColor Red
    exit 1
}

# Test Docker daemon connectivity
$dockerDaemonOk = $false
try {
    $null = docker info 2>$null
    if ($LASTEXITCODE -eq 0) {
        $dockerDaemonOk = $true
    }
} catch {
    $dockerDaemonOk = $false
}

if (-not $dockerDaemonOk) {
    Write-Host "[ERROR] Docker Engine / Desktop is not running or unreachable." -ForegroundColor Red
    Write-Host "        Please start Docker Desktop and try again." -ForegroundColor DarkYellow
    exit 1
}
Write-Host "  [OK] Docker Engine is running" -ForegroundColor Green

# 2. Verify and Load .env
Write-Host "`n[2/5] Verifying environment configuration (.env)..." -ForegroundColor Yellow
$envPath = Join-Path $ProjectRoot ".env"
$envExamplePath = Join-Path $ProjectRoot ".env.example"

if (-not (Test-Path $envPath)) {
    if (Test-Path $envExamplePath) {
        Write-Host "  --> .env not found; copying from .env.example..." -ForegroundColor DarkYellow
        Copy-Item $envExamplePath $envPath
    } else {
        Write-Host "[ERROR] Neither .env nor .env.example found at project root." -ForegroundColor Red
        exit 1
    }
}

# Export .env key-values into current session environment
Get-Content $envPath | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not ($line.StartsWith("#"))) {
        $parts = $line.Split("=", 2)
        if ($parts.Length -eq 2) {
            $key = $parts[0].Trim()
            $val = $parts[1].Trim()
            [System.Environment]::SetEnvironmentVariable($key, $val, [System.EnvironmentVariableTarget]::Process)
        }
    }
}
Write-Host "  [OK] Environment variables loaded from .env" -ForegroundColor Green
Write-Host "       DB_HOST: $($env:DB_HOST)" -ForegroundColor DarkGray
Write-Host "       DB_PORT: $($env:DB_PORT)" -ForegroundColor DarkGray
Write-Host "       DB_NAME: $($env:DB_NAME)" -ForegroundColor DarkGray
Write-Host "       SERVER_PORT: $($env:SERVER_PORT)" -ForegroundColor DarkGray

# 3. Start PostgreSQL
Write-Host "`n[3/5] Starting PostgreSQL container (aurora-postgres)..." -ForegroundColor Yellow
Push-Location $ProjectRoot
try {
    docker compose up -d aurora-postgres
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Failed to start aurora-postgres container via docker compose." -ForegroundColor Red
        exit 1
    }
} finally {
    Pop-Location
}

# 4. Wait for PostgreSQL Health
Write-Host "`n[4/5] Waiting for PostgreSQL container to become healthy..." -ForegroundColor Yellow
$maxAttempts = 30
$attempt = 0
$healthy = $false

while ($attempt -lt $maxAttempts) {
    $rawStatus = docker inspect --format "{{json .State.Health.Status}}" aurora-postgres 2>$null
    if ($rawStatus) {
        $status = ($rawStatus -replace '[^a-zA-Z0-9_-]', '')
        if ($status -eq "healthy") {
            $healthy = $true
            break
        }
    }
    $attempt++
    Start-Sleep -Seconds 1
}

if (-not $healthy) {
    Write-Host "[ERROR] PostgreSQL container failed to become healthy within $maxAttempts seconds." -ForegroundColor Red
    docker compose -f (Join-Path $ProjectRoot "docker-compose.yml") logs aurora-postgres
    exit 1
}
Write-Host "  [OK] aurora-postgres is healthy and ready on port $($env:DB_PORT)" -ForegroundColor Green

if ($DatabaseOnly) {
    Write-Host "`n[DONE] PostgreSQL is running and healthy (-DatabaseOnly specified)." -ForegroundColor Green
    exit 0
}

# 5. Launch AURORA Control Plane
Write-Host "`n[5/5] Launching AURORA Control Plane (Spring Boot)..." -ForegroundColor Cyan
Write-Host "------------------------------------------------------------------" -ForegroundColor DarkGray

$targetPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }
$existingConn = Get-NetTCPConnection -LocalPort $targetPort -ErrorAction SilentlyContinue | Where-Object { $_.State -eq "Listen" }

if ($existingConn) {
    $existingPid = $existingConn[0].OwningProcess
    if ($Restart) {
        Write-Host "  --> Stopping existing process on port $targetPort (PID $existingPid)..." -ForegroundColor Yellow
        Stop-Process -Id $existingPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    } else {
        try {
            $health = Invoke-RestMethod -Uri "http://localhost:$targetPort/actuator/health" -TimeoutSec 2 -ErrorAction Stop
            if ($health.status -eq "UP") {
                Write-Host "`n[ALREADY RUNNING] AURORA Control Plane is already active and healthy on port $targetPort (PID $existingPid)." -ForegroundColor Green
                Write-Host "  Actuator Health: http://localhost:$targetPort/actuator/health" -ForegroundColor Cyan
                Write-Host "  Resource API:    http://localhost:$targetPort/api/v1/resources" -ForegroundColor Cyan
                Write-Host "`nTo restart it, run: .\scripts\start-dev.ps1 -Restart" -ForegroundColor DarkGray
                exit 0
            }
        } catch {}
        Write-Host "  [WARNING] Port $targetPort is already in use by process PID $existingPid." -ForegroundColor Yellow
        Write-Host "  Run with -Restart flag to stop PID $existingPid and restart cleanly: .\scripts\start-dev.ps1 -Restart" -ForegroundColor Yellow
        exit 1
    }
}

Push-Location $ControlPlaneDir
try {
    $mvnCmd = Join-Path $ControlPlaneDir "mvnw.cmd"
    if (Test-Path $mvnCmd) {
        & $mvnCmd spring-boot:run
    } else {
        mvn spring-boot:run
    }
} finally {
    Pop-Location
}
