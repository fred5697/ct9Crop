# KEYBOARD FIX GUIDE FOR ANDROID STUDIO ON MACBOOK BOOTCAMP WINDOWS 10

## Issues Fixed:
1. Keyboard keys getting stuck
2. Keyboard disappearing/not responding
3. Input lag in editor
4. IME conflicts

## Applied Fixes:

### 1. VM Options File Created
Location: C:\Users\fred\AppData\Roaming\Google\AndroidStudio2025.2.1\studio64.exe.vmoptions
- Disables DirectWrite and Direct3D (causes input issues on Bootcamp)
- Fixes IME (Input Method Editor) conflicts
- Disables problematic animations
- Optimizes memory for stability

### 2. Editor Settings Configured
Location: C:\Users\fred\AppData\Roaming\Google\AndroidStudio2025.2.1\options\editor.xml
- Disabled animated scrolling (reduces input lag)
- Disabled mouse wheel font changes (prevents accidental triggers)
- Set proper caret blinking

### 3. Registry Fix Created
Location: C:\Users\fred\AndroidStudioProjects\CT9Crop\fix_keyboard_bootcamp.reg
- Disables Windows IME indicator
- Disables text prediction/autocorrection that conflicts with IDE

## STEPS TO APPLY:

### Step 1: Close Android Studio completely
Make sure Android Studio is fully closed.

### Step 2: Apply Registry Fix (IMPORTANT!)
1. Navigate to: C:\Users\fred\AndroidStudioProjects\CT9Crop\
2. Double-click: fix_keyboard_bootcamp.reg
3. Click "Yes" to add to registry
4. Restart your computer (important for registry changes)

### Step 3: Restart Android Studio
After reboot, start Android Studio normally.

### Step 4: Additional In-IDE Settings (Do after restart)
In Android Studio:
1. Go to: File → Settings (Ctrl+Alt+S)
2. Navigate to: Editor → General
   - UNCHECK "Change font size with Ctrl+Mouse Wheel"
   - UNCHECK "Use soft wraps in editor"
3. Navigate to: Appearance & Behavior → System Settings
   - UNCHECK "Reopen projects on startup" (optional, improves startup)
   - UNCHECK "Confirm application exit"
4. Navigate to: Editor → General → Code Completion
   - Set "Autopopup code completion" delay to 500ms (reduces interruptions)
5. Navigate to: Tools → Actions on Save
   - UNCHECK all options (prevents unexpected formatting during typing)

### Step 5: Boot Camp Drivers (If still having issues)
Make sure you have latest Boot Camp drivers:
1. Open Boot Camp Assistant on macOS side
2. Download latest Boot Camp Support Software
3. Install on Windows

### Step 6: Windows Language Settings
1. Open Windows Settings → Time & Language → Language
2. Make sure only ONE input language is active
3. Remove any unnecessary IME (Chinese, Japanese, Korean) if not needed

## Additional Troubleshooting:

### If keyboard still sticks:
1. In Android Studio, try: Help → Edit Custom VM Options
2. Add these additional lines:
   -Dide.debounce.threshold=100
   -Didea.true.smooth.scrolling=false

### If keyboard disappears:
1. Press Alt+Tab to switch away and back
2. Or press Windows key and click back on Android Studio
3. This forces Windows to reset input focus

### MacBook Pro A2251 Specific:
Your MacBook Pro A2251 has a Touch Bar and butterfly/scissor keyboard.
In Windows:
1. Make sure Boot Camp 6.1.7769 or later is installed
2. Check Device Manager → Keyboards → Apple Keyboard
3. Right-click → Update Driver → Search automatically

### Performance Tip:
The reduced memory settings (1GB min, 2GB max) are intentional.
Boot Camp on MacBook Pro sometimes has memory management issues
that cause input lag. The lower settings improve stability.

## Test After Applying:
1. Open a Java file in Android Studio
2. Try typing continuously for 30 seconds
3. Try pressing and holding a key
4. Try using shortcuts (Ctrl+C, Ctrl+V, etc.)
5. Try typing while scrolling

## If Issues Persist:
Consider these alternatives:
1. Use External Keyboard (USB/Bluetooth) - often works perfectly
2. Increase Boot Camp partition size if < 50GB free
3. Disable Windows Defender real-time scanning for project folder
4. Use IntelliJ IDEA Community Edition (lighter than Android Studio)

## Revert Changes:
If you need to undo:
1. Delete: C:\Users\fred\AppData\Roaming\Google\AndroidStudio2025.2.1\studio64.exe.vmoptions
2. Android Studio will regenerate defaults on next start

