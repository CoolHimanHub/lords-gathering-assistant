# Implementation Checklist & Testing Guide

## ✅ Completed Components

### Core System
- [x] **AutoGatheringEngine.kt** - Automated gathering cycle manager
  - Target selection and prioritization
  - Gesture-based troop dispatch
  - Configurable gathering intervals
  
- [x] **ScreenAnalyzer.kt** - RSS tile detection engine
  - Color-based resource identification
  - Badge region detection via pixel analysis
  - Level estimation (1-5)
  - Occupancy detection
  - Confidence scoring (0-100)

- [x] **GatheringAccessibilityService.kt** - Main service orchestrator
  - Screenshot capture integration
  - Screen analysis coordination
  - Auto-gathering state management
  - UI overlay with real-time updates
  - Three control modes (SCAN, ONE SCAN, AUTO GATHER)

### UI & Configuration
- [x] Control overlay with draggable title bar
- [x] Three action buttons (SCAN, ONE SCAN, AUTO GATHER)
- [x] Real-time status display
- [x] Resource priority settings in MainActivity
- [x] Dynamic button text updates

### Documentation
- [x] README.md - Feature overview and quick start
- [x] SETUP_GUIDE.md - Detailed configuration and troubleshooting
- [x] Inline code documentation

---

## 🧪 Testing Phase

### Pre-Testing Setup
```bash
# 1. Build the project
./gradlew clean build

# 2. Install debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Enable accessibility service
adb shell settings put secure enabled_accessibility_services com.coolhimanhub.lordsgatheringassistant/.GatheringAccessibilityService

# 4. Grant permissions
adb shell pm grant com.coolhimanhub.lordsgatheringassistant android.permission.SYSTEM_ALERT_WINDOW

# 5. View logs
adb logcat | grep -i lords
```

### Test 1: Service Startup
**Objective:** Verify service initializes correctly
```
Steps:
1. Open app
2. Press "ENABLE / MANAGE ACCESSIBILITY SERVICE"
3. Verify overlay appears with 3 buttons
4. Verify drag handle works
5. Close and reopen app

Expected Result:
✓ Overlay appears on top
✓ All buttons visible and responsive
✓ Status text shows "V12 Scanner ready"
```

### Test 2: Manual Scan
**Objective:** Test single screenshot capture
```
Steps:
1. Launch Lords Mobile game
2. Navigate to map with RSS tiles visible
3. Press "🔍 ONE SCAN" button
4. Wait 2-3 seconds

Expected Result:
✓ Status shows "Capturing screen..."
✓ Then "Analyzing screen..."
✓ Displays detected tile count
✓ Shows tile details (type, level, confidence)
```

### Test 3: Auto-Scan Mode
**Objective:** Test continuous scanning
```
Steps:
1. Press "▶ SCAN" button
2. Observe status updates every 4 seconds
3. Verify tile detection continues
4. Press "⏸ STOP" after 30 seconds

Expected Result:
✓ Button text changes to "⏸ STOP"
✓ Scan #N increments every 4 seconds
✓ Tile counts update in real-time
✓ No crashes or freezes
```

### Test 4: Detection Accuracy
**Objective:** Verify tile detection works correctly
```
Steps:
1. Open map view with various RSS tiles
2. Take ONE SCAN
3. Manually count visible tiles
4. Compare with app detection

Expected Result:
✓ Most visible tiles detected
✓ Confidence scores realistic (70-100%)
✓ Resource types correctly identified
✓ Levels estimated reasonably (±1 level)
```

### Test 5: Occupancy Detection
**Objective:** Verify occupied tile identification
```
Steps:
1. Navigate to map with some occupied tiles
2. Take ONE SCAN
3. Check detected tiles for "⛔" flag

Expected Result:
✓ Occupied tiles marked with ⛔
✓ Unoccupied tiles have no flag
✓ Occupancy matches visual inspection
```

### Test 6: Auto-Gathering Flow
**Objective:** Test full gathering cycle (SAFE MODE - no actual troops)
```
Steps:
1. Open game and navigate to map
2. Press "⚔ AUTO GATHER" button
3. Observe for 3-4 cycles (24-32 seconds)
4. Check status updates
5. Press "⏸ STOP GATHER" to halt

Expected Result:
✓ Button changes to "⏸ STOP GATHER"
✓ Status shows "AUTO GATHERING STARTED"
✓ Detects targets every 8 seconds
✓ Logs target selection ("Targeting: Gold L5")
✓ No crashes or hangs
```

### Test 7: Priority System
**Objective:** Verify resource prioritization works
```
Steps:
1. Set resource priority in MainActivity:
   - Emerging, Gold, Ore, Wood, Food, Stone
2. Start AUTO GATHER
3. Observe selected targets

Expected Result:
✓ Targets selected in priority order
✓ Prefers higher priority resources
✓ Still considers level and confidence
```

### Test 8: Configuration Changes
**Objective:** Test parameter adjustment
```
Steps:
1. Edit minimumConfidence from 8 to 20:
   - GatheringAccessibilityService.kt line 111
2. Rebuild: ./gradlew assembleDebug
3. Reinstall and test

Expected Result:
✓ Fewer tiles detected (higher threshold)
✓ Only high-confidence tiles shown
✓ No build or runtime errors
```

### Test 9: Error Handling
**Objective:** Test resilience to errors
```
Steps:
1. Interrupt app while scanning (press back)
2. Lock/unlock screen during gathering
3. Rotate device orientation
4. Disconnect/reconnect USB
5. Low memory conditions

Expected Result:
✓ No crashes
✓ Service recovers gracefully
✓ Overlay survives screen changes
✓ Proper cleanup on exit
```

### Test 10: Performance
**Objective:** Verify app doesn't drain battery/RAM
```
Steps:
1. Check device memory before: adb shell dumpsys meminfo
2. Run AUTO GATHER for 5 minutes
3. Check device memory after
4. Monitor battery usage
5. Check CPU usage via adb top

Expected Result:
✓ Memory increase < 50MB
✓ No memory leaks
✓ CPU usage < 10% when idle
✓ Battery drain minimal
```

---

## 🔍 Visual Verification Checklist

### Overlay Display
- [ ] Title bar displays "Lords Assistant V12"
- [ ] Three buttons clearly visible and spaced
- [ ] Status text is readable (white on gray background)
- [ ] Text scrolls when content overflows
- [ ] Drag handle responds to touch
- [ ] Overlay doesn't obstruct critical game UI

### Button Functionality
- [ ] "▶ SCAN" → "⏸ STOP" toggle works
- [ ] "🔍 ONE SCAN" always clickable
- [ ] "⚔ AUTO GATHER" → "⏸ STOP GATHER" toggle works
- [ ] Button clicks register immediately
- [ ] No duplicate/multiple triggers on single click

### Status Messages
- [ ] Clear and informative
- [ ] Updates in real-time
- [ ] Formatting is readable
- [ ] Error messages are descriptive
- [ ] Scan numbers increment correctly

---

## 📊 Detection Calibration

### Color Calibration Process

1. **Capture Target Screenshot**
   ```bash
   adb shell screencap -p /sdcard/DCIM/target.png
   adb pull /sdcard/DCIM/target.png
   ```

2. **Analyze Actual Colors**
   - Use image editor (GIMP, Photoshop) color picker
   - Sample center of each resource type badge
   - Record RGB values

3. **Update RESOURCE_COLORS**
   ```kotlin
   private val RESOURCE_COLORS = mapOf(
       "Gold" to Triple(R, G, B),  // Your measured values
       "Ore" to Triple(R, G, B),
       // ... etc
   )
   ```

4. **Test Detection**
   - Run ONE SCAN
   - Verify detection accuracy improves
   - Adjust RGB values if needed

### Gesture Coordinate Calibration

1. **Identify Button Locations**
   - Send Troops button center: (X, Y)
   - Confirm button center: (X, Y)

2. **Update Coordinates in AutoGatheringEngine**
   ```kotlin
   private fun sendTroops() {
       tapLocation(X, Y)  // Send button
   }
   
   private fun confirmSendTroops() {
       tapLocation(X, Y)  // Confirm button
   }
   ```

3. **Test with Manual Taps**
   - Verify tap locations are accurate
   - Adjust if buttons are missed

---

## 🐛 Common Issues & Solutions

### Issue: No tiles detected
**Solution:**
1. Run ONE SCAN
2. Check status message
3. Lower `minimumConfidence` to 5
4. Check game version vs color definitions
5. Verify map is fully visible

### Issue: False positives
**Solution:**
1. Increase `minimumConfidence` to 15-20
2. Update `RESOURCE_COLORS` for accuracy
3. Verify no UI elements resemble badges

### Issue: App crashes
**Solution:**
1. Check logcat: `adb logcat | grep AndroidRuntime`
2. Verify accessibility permissions
3. Restart service: `adb shell am restart`
4. Rebuild project: `./gradlew clean build`

### Issue: Troops not sending
**Solution:**
1. Verify gesture coordinates are correct
2. Increase TROOP_SEND_DELAY (try 1000ms)
3. Ensure tile popup appears after tap
4. Check troops available in castle

---

## 📋 Pre-Release Checklist

- [ ] All tests pass (1-10)
- [ ] No crashes or exceptions in logcat
- [ ] Performance acceptable (< 50MB RAM)
- [ ] Detection accuracy > 80%
- [ ] All buttons functional
- [ ] Status display clear and updated
- [ ] Documentation complete and accurate
- [ ] Code properly formatted and documented
- [ ] Build succeeds without warnings
- [ ] APK installs and runs without errors

---

## 🚀 Deployment Steps

1. **Final Build**
   ```bash
   ./gradlew clean assembleRelease
   ```

2. **Test Release Build**
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

3. **Verify Functionality**
   - Run all 10 tests again
   - Check performance on various devices

4. **Document Results**
   - Record test outcomes
   - Note any device-specific issues
   - Update SETUP_GUIDE.md if needed

5. **Version Management**
   - Update version code in build.gradle
   - Tag commit: `git tag v1.2.0`
   - Create release notes

---

## 📞 Support & Debugging

### Enable Verbose Logging
```bash
# Add to code:
Log.d("LordsAssistant", "Debug message")

# View logs:
adb logcat | grep "LordsAssistant"
```

### Common Debug Commands
```bash
# Check service status
adb shell dumpsys accessibility

# View service state
adb shell pm dump com.coolhimanhub.lordsgatheringassistant

# Clear app data
adb shell pm clear com.coolhimanhub.lordsgatheringassistant

# Restart service
adb shell am force-stop com.coolhimanhub.lordsgatheringassistant
```

---

## 📈 Success Metrics

✅ **Core Functionality:**
- Detection accuracy: > 80%
- Gesture reliability: > 95%
- Uptime: > 99% (no crashes)

✅ **Performance:**
- Memory usage: < 100MB
- CPU usage (idle): < 5%
- Scan time: < 2 seconds

✅ **User Experience:**
- Clear status display
- Responsive controls
- Intuitive UI
- Helpful error messages

---

**Testing Status:** Ready for QA  
**Last Updated:** 2026-09-10  
**Next Phase:** User testing and feedback collection

For detailed configuration and troubleshooting, see [SETUP_GUIDE.md](SETUP_GUIDE.md)
