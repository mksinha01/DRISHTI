@echo off
TITLE Drishti Agent Console Mode
echo ========================================================
echo        Starting Drishti Agent in Console Mode (Groq)
echo ========================================================
echo.
echo Choose console mode:
echo [1] Voice Mode (Talk using your laptop mic and speaker)
echo [2] Text Mode  (Chat by typing in this terminal)
echo.
set /p choice="Select option (1 or 2, default is 1): "

cd /d "%~dp0\agent"

if "%choice%"=="2" (
    echo Starting Console Text Mode...
    uv run --with-requirements requirements.txt python drishti_agent.py console --text
) else (
    echo Starting Console Voice Mode...
    uv run --with-requirements requirements.txt python drishti_agent.py console
)

pause
