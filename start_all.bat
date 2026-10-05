@echo off
TITLE Drishti Multi-Service Launcher
echo ========================================================
echo        Starting Drishti Backend Services
echo ========================================================
echo.

REM 1. Start Token Server
echo [1/2] Starting LiveKit Token Server on port 8000 with uv...
start "Drishti Token Server" cmd /k "cd token-server && uv run --with-requirements requirements.txt uvicorn main:app --host 0.0.0.0 --port 8000 --reload"

timeout /t 2 /nobreak >nul

REM 2. Start LiveKit Agent Worker
echo [2/2] Starting Drishti LiveKit Agent Worker with uv...
start "Drishti Agent Worker" cmd /k "cd agent && uv run --with-requirements requirements.txt python drishti_agent.py dev"

echo.
echo All backend services launched with full uv dependency environments!
echo Token Server: http://localhost:8000/health
echo Press any key to exit this launcher...
pause >nul
