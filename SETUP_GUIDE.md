# Lords Gathering Assistant - Auto-Gathering System

## Overview

This is a complete auto-gathering system for Lords Gathering that automatically detects RSS (Resource) tiles on the game map and sends troops to gather them.

## System Architecture

```
GatheringAccessibilityService (Main Service)
├── ScreenAnalyzer (Tile Detection)
├── AutoGatheringEngine (Gathering Logic)
└── UI Overlay (Controls & Status)
```

## Components

### 1. **ScreenAnalyzer.kt**
Detects RSS tile badges in game screenshots using:
- Color detection and matching
- Badge region finding via pixel intensity analysis
- Level estimation from badge characteristics
- Occupancy detection (red flag overlay)
- Confidence scoring

**Features:**
- Detects all 7 resource types: Emerging, Gold, Ore, Wood, Food, Stone, Other
- Estimates levels 1-5 based on badge size and brightness
- Identifies occupied tiles
- Returns 0-100 confidence score per detection

### 2. **AutoGatheringEngine.kt**
Manages the automated gathering cycle:
- Target prioritization and selection
- Gesture-based troop sending
- Configurable gathering intervals
- Supports different troop types

**Configuration:**
- `GATHER_CYCLE_INTERVAL` = 8000ms (8 seconds)
- `TROOP_SEND_DELAY` = 500ms (before sending)
- `CONFIRM_DIALOG_DELAY` = 300ms (for confirmation)
- `GESTURE_DURATION` = 100ms (tap duration)

### 3. **GatheringAccessibilityService.kt**
Main service that orchestrates everything:
- Captures screenshots periodically
- Analyzes screens for RSS tiles
- Filters results by confidence threshold
- Controls auto-gathering state
- Updates UI with status and results

## UI Controls

### Buttons:

| Button | Action | Function |
|--------|--------|----------|
| **▶ SCAN** | Toggle | Auto-scan every 4 seconds (display only) |
| **🔍 ONE SCAN** | Single | One manual screenshot capture |
| **⚔ AUTO GATHER** | Toggle | Automated gathering with troop dispatch |

### Status Display:
Real-time updates showing:
- Current scan number
- Detected tiles and their properties
- Gathering status (ACTIVE/IDLE)
- Confidence levels
- Error messages

## Configuration

### Adjustable Parameters

```kotlin
// Scan interval (ms)
private val scanInterval = 4000L

// Minimum confidence threshold (0-100)
private val minimumConfidence = 8

// Maximum tiles to display
private val maximumDisplayedTargets = 20

// RSS priority order
private val rssPriority = listOf(
    "Emerging", "Gold", "Ore", "Wood", 
    "Food", "Stone", "Other"
)
```

### Resource Colors (ScreenAnalyzer)
Customize detection colors in `ScreenAnalyzer.kt`:
```kotlin
private val RESOURCE_COLORS = mapOf(
    "Emerging" to Triple(255, 215, 0),  // Gold
    "Gold" to Triple(255, 200, 0),      // Dark Gold
    "Ore" to Triple(128, 128, 128),     // Gray
    // ... etc
)
```

### Gesture Coordinates (AutoGatheringEngine)
Adjust tap locations for your game UI:
```kotlin
// Send button location
private fun sendTroops() {
    tapLocation(540, 1100)  // Adjust X, Y
    
    // Confirm button location
    private fun confirmSendTroops() {
        tapLocation(720, 1200)  // Adjust X, Y
    }
}
```

## Installation & Setup

### 1. Enable Accessibility Service
- Go to: Settings > Accessibility > Accessibility Services
- Enable "Lords Gathering Assistant"
- Grant all requested permissions

### 2. Launch App
- Open Lords Gathering Assistant
- Press "ENABLE / MANAGE ACCESSIBILITY SERVICE" button if needed
- Overlay will appear with control buttons

### 3. Test Detection
- Press **🔍 ONE SCAN** to capture screen
- Check overlay for detected tiles
- Verify accuracy and adjust detection thresholds if needed

### 4. Start Auto-Gathering
- Press **⚔ AUTO GATHER** button
- System will automatically:
  - Scan every 8 seconds
  - Select best target
  - Send troops
  - Repeat

## Detection Tuning

### If tiles are not being detected:

1. **Check Confidence Threshold**
   - Reduce `minimumConfidence` (currently 8)
   - Range: 0-100

2. **Adjust Resource Colors**
   - Take a screenshot while running
   - Check detected tile colors in logs
   - Update `RESOURCE_COLORS` map in ScreenAnalyzer

3. **Verify Scan Region**
   - Ensure map area is not obstructed
   - Clear any overlapping UI elements
   - Center the map view

### If false positives occur:

1. **Increase Confidence Threshold**
   - Raise `minimumConfidence` value
   - Filters out low-quality detections

2. **Refine Color Matching**
   - Tighten RGB color ranges
   - Adjust `RGB_THRESHOLD` in ScreenAnalyzer

3. **Adjust Badge Detection**
   - Modify `minBadgeSize` (30px minimum)
   - Modify `maxBadgeSize` (150px maximum)

## Performance Optimization

### Adjust Scanning Speed
```kotlin
private val scanInterval = 4000L  // Reduce for faster scanning (2000L = 2 seconds)
```

### Limit Displayed Results
```kotlin
private val maximumDisplayedTargets = 20  // Reduce for faster processing
```

### Execution Thread
- Detection runs on `analysisExecutor` (background thread)
- Prevents UI blocking
- Safe concurrent operations

## Safety Features

### Built-in Safeguards:
1. ✅ No map movement - Only taps on tiles
2. ✅ Troop type selectable - Customize which troops to send
3. ✅ Manual override - Press STOP to halt at any time
4. ✅ Occupancy detection - Avoids occupied tiles
5. ✅ Gesture-based - Uses accessibility gestures (no injected input)

### Best Practices:
- Start with ONE SCAN to verify detection
- Monitor first few auto-gathers manually
- Adjust coordinates based on your device
- Test with lower troop counts first
- Use unoccupied resource zones

## Troubleshooting

### Tiles not detected?
```
1. Run ONE SCAN
2. Check overlay status
3. Verify game is running properly
4. Check Android version (requires Android 11+)
5. Increase MAX_DISPLAYED_TARGETS to see all detections
```

### Troops not sending?
```
1. Verify gesture coordinates are correct
2. Check if tile details popup appears
3. Add delays if game is slow (TROOP_SEND_DELAY)
4. Ensure sufficient troop count in castle
```

### App crashes?
```
1. Check logcat for exceptions
2. Verify accessibility service has all permissions
3. Restart app if service becomes unresponsive
4. Check RAM usage (reduce MAXIMUM_DISPLAYED_TARGETS)
```

### Low accuracy?
```
1. Take ONE SCAN and review results
2. Adjust MINIMUMCONFIDENCE value (lower = more detections)
3. Update RESOURCE_COLORS based on actual game colors
4. Verify lighting/contrast on your device
```

## Files Structure

```
app/src/main/java/com/coolhimanhub/lordsgathering/
├── GatheringAccessibilityService.kt    # Main service (orchestrator)
├── ScreenAnalyzer.kt                   # Tile detection engine
├── AutoGatheringEngine.kt              # Gathering logic
└── MainActivity.kt                     # Settings & RSS priority
```

## Version History

- **V12** - Full auto-gathering with ScreenAnalyzer integration
- **V11** - Initial scanner with manual tile detection
- **V10** - Basic accessibility service framework

## Future Enhancements

- [ ] Machine learning-based tile detection
- [ ] Pathfinding for troop movement
- [ ] Multi-castle support
- [ ] Resource farming strategy AI
- [ ] Damage assessment and healing
- [ ] Event farming automation
- [ ] Data persistence and logging

## Legal Notice

This tool is designed for personal, private use only. Users are responsible for compliance with the game's terms of service. Automated gathering may violate game rules - use at your own risk.

## Support

For issues or questions:
1. Check the Troubleshooting section
2. Review logcat output
3. Test with ONE SCAN first
4. Adjust configuration gradually

---

**Made with ⚔️ for Lords Mobile Players**
