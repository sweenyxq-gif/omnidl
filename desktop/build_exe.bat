@echo off
echo ===================================================
echo Building OmniDownloader Desktop Standalone Executable (.exe)
echo ===================================================

cd /d "%~dp0.."
python -m PyInstaller ^
    --noconfirm ^
    --onedir ^
    --windowed ^
    --name "OmniDownloader" ^
    --collect-all "customtkinter" ^
    desktop/main.py

echo.
echo ===================================================
echo Build complete! Executable is in: dist\OmniDownloader\OmniDownloader.exe
echo ===================================================
pause
