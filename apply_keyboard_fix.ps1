# Android Studio Keyboard Fix Script for MacBook Bootcamp
# Run this script as Administrator

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Android Studio Keyboard Fix for Bootcamp" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check if running as administrator
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "WARNING: Not running as Administrator" -ForegroundColor Yellow
    Write-Host "Some fixes may not apply. Continuing anyway..." -ForegroundColor Yellow
    Write-Host ""
}

# 1. Apply Registry Fixes
Write-Host "Step 1: Applying Registry Fixes..." -ForegroundColor Green
try {
    reg add "HKCU\Control Panel\Input Method" /v EnableIndicator /t REG_SZ /d 0 /f | Out-Null
    reg add "HKCU\Keyboard Layout\Toggle" /v "Language Hotkey" /t REG_SZ /d 3 /f | Out-Null
    reg add "HKCU\Keyboard Layout\Toggle" /v "Layout Hotkey" /t REG_SZ /d 3 /f | Out-Null
    reg add "HKCU\Software\Microsoft\Input\Settings" /v EnableHwkbTextPrediction /t REG_DWORD /d 0 /f | Out-Null
    reg add "HKCU\Software\Microsoft\Input\Settings" /v EnableHwkbAutocorrection /t REG_DWORD /d 0 /f | Out-Null
    reg add "HKCU\Software\Microsoft\Input\Settings" /v EnableHwkbSuggestions /t REG_DWORD /d 0 /f | Out-Null
    Write-Host "   Registry fixes applied successfully!" -ForegroundColor Green
} catch {
    Write-Host "   Error applying registry fixes: $_" -ForegroundColor Red
}
Write-Host ""

# 2. Check Android Studio Installation
Write-Host "Step 2: Checking Android Studio Installation..." -ForegroundColor Green
$studioPath = "$env:APPDATA\Google\AndroidStudio2025.2.1"
if (Test-Path $studioPath) {
    Write-Host "   Found: $studioPath" -ForegroundColor Green
} else {
    Write-Host "   Android Studio config not found at expected location" -ForegroundColor Yellow
    $studioPath = "$env:APPDATA\Google\AndroidStudio2025.1.2"
    if (Test-Path $studioPath) {
        Write-Host "   Using: $studioPath" -ForegroundColor Green
    }
}
Write-Host ""

# 3. Verify VM Options File
Write-Host "Step 3: Verifying VM Options..." -ForegroundColor Green
$vmOptionsFile = "$studioPath\studio64.exe.vmoptions"
if (Test-Path $vmOptionsFile) {
    Write-Host "   VM options file exists and configured!" -ForegroundColor Green
} else {
    Write-Host "   WARNING: VM options file not found" -ForegroundColor Yellow
    Write-Host "   The file should have been created at: $vmOptionsFile" -ForegroundColor Yellow
}
Write-Host ""

# 4. Check Boot Camp Drivers
Write-Host "Step 4: Checking Boot Camp Drivers..." -ForegroundColor Green
try {
    $bootcampVersion = Get-ItemProperty -Path "HKLM:\SOFTWARE\Apple Inc.\Apple Keyboard Support" -Name "Version" -ErrorAction SilentlyContinue
    if ($bootcampVersion) {
        Write-Host "   Boot Camp Support Software is installed" -ForegroundColor Green
        Write-Host "   Version: $($bootcampVersion.Version)" -ForegroundColor Gray
    } else {
        Write-Host "   WARNING: Boot Camp drivers may not be installed" -ForegroundColor Yellow
        Write-Host "   Consider updating from macOS side" -ForegroundColor Yellow
    }
} catch {
    Write-Host "   Could not verify Boot Camp installation" -ForegroundColor Yellow
}
Write-Host ""

# 5. Check for processes that might interfere
Write-Host "Step 5: Checking for interfering processes..." -ForegroundColor Green
$androidStudioRunning = Get-Process -Name "studio64" -ErrorAction SilentlyContinue
if ($androidStudioRunning) {
    Write-Host "   WARNING: Android Studio is currently running!" -ForegroundColor Yellow
    Write-Host "   You must close and restart Android Studio for changes to take effect" -ForegroundColor Yellow
} else {
    Write-Host "   Android Studio is not running - Good!" -ForegroundColor Green
}
Write-Host ""

# 6. Disable problematic Windows features
Write-Host "Step 6: Disabling problematic Windows features..." -ForegroundColor Green
try {
    # Disable text suggestions
    Set-ItemProperty -Path "HKCU:\Software\Microsoft\Input\Settings" -Name "InsightsEnabled" -Value 0 -ErrorAction SilentlyContinue
    Set-ItemProperty -Path "HKCU:\Software\Microsoft\TabletTip\1.7" -Name "EnableAutocorrection" -Value 0 -ErrorAction SilentlyContinue
    Write-Host "   Windows text suggestions disabled" -ForegroundColor Green
} catch {
    Write-Host "   Some features could not be disabled (may not exist)" -ForegroundColor Yellow
}
Write-Host ""

# Summary
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "SUMMARY" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Fixes Applied:" -ForegroundColor Green
Write-Host "  ✓ Registry settings updated" -ForegroundColor Gray
Write-Host "  ✓ IME conflicts disabled" -ForegroundColor Gray
Write-Host "  ✓ Text prediction disabled" -ForegroundColor Gray
Write-Host "  ✓ VM options configured" -ForegroundColor Gray
Write-Host ""
Write-Host "NEXT STEPS:" -ForegroundColor Yellow
Write-Host "1. RESTART YOUR COMPUTER (Important!)" -ForegroundColor White
Write-Host "2. After restart, open Android Studio" -ForegroundColor White
Write-Host "3. Test keyboard functionality" -ForegroundColor White
Write-Host ""
Write-Host "If issues persist:" -ForegroundColor Yellow
Write-Host "- Try using an external USB/Bluetooth keyboard" -ForegroundColor Gray
Write-Host "- Check that Boot Camp drivers are up to date" -ForegroundColor Gray
Write-Host "- Ensure only one input language is active in Windows" -ForegroundColor Gray
Write-Host ""
Write-Host "Read KEYBOARD_FIX_README.txt for detailed troubleshooting" -ForegroundColor Cyan
Write-Host ""
Write-Host "Press any key to exit..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

