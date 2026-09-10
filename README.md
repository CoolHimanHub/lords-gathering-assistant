# Lords Gathering Assistant

A powerful Android accessibility service for automated RSS gathering in Lords Mobile game.

## 🎯 Features

✅ **Automatic RSS Tile Detection** - Uses advanced color analysis to identify all resource types
✅ **Auto-Gathering System** - Automatically sends troops to gather resources every 8 seconds
✅ **Smart Prioritization** - Gather resources in your preferred order
✅ **Occupancy Detection** - Avoids occupied tiles automatically
✅ **Real-time Status** - Live overlay showing detected tiles and gathering progress
✅ **Customizable** - Adjust detection thresholds, gathering intervals, and resource priorities
✅ **Safe & Reliable** - Gesture-based with manual override capability

## 📱 System Requirements

- Android 11+ (R or later)
- Accessibility Service support
- Lords Mobile game installed
- Minimum 2GB RAM

## 🚀 Quick Start

### Installation

1. **Build & Install**
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Enable Accessibility Service**
   - Settings → Accessibility → Accessibility Services
   - Enable "Lords Gathering Assistant"
   - Grant all permissions

3. **Launch the App**
   - Open Lords Gathering Assistant
   - Press "ENABLE / MANAGE ACCESSIBILITY SERVICE" if needed
   - Control overlay will appear on screen

### Basic Usage

| Button | Purpose |
|--------|---------|
| **▶ SCAN** | Auto-scan map every 4 seconds (view only) |
| **🔍 ONE SCAN** | Single manual capture for testing |
| **⚔ AUTO GATHER** | Start automated gathering |

## 📖 Documentation

- **[SETUP_GUIDE.md](SETUP_GUIDE.md)** - Detailed setup and configuration guide
- **Configuration** - See SETUP_GUIDE.md for customization options
- **Troubleshooting** - See SETUP_GUIDE.md for common issues

## 🔧 Configuration

### Adjustable Parameters

Edit in `GatheringAccessibilityService.kt`:

```kotlin
// Scan frequency (milliseconds)
private val scanInterval = 4000L

// Minimum detection confidence (0-100)
private val minimumConfidence = 8

// Maximum tiles to display
private val maximumDisplayedTargets = 20

// RSS gathering priority
private val rssPriority = listOf(
    "Emerging", "Gold", "Ore", "Wood", 
    "Food", "Stone", "Other"
)
```

### Resource Colors

Modify detection in `ScreenAnalyzer.kt`:

```kotlin
private val RESOURCE_COLORS = mapOf(
    "Gold" to Triple(255, 200, 0),
    "Ore" to Triple(128, 128, 128),
    // ... customize for your game version
)
```

### Gesture Coordinates

Update tap locations in `AutoGatheringEngine.kt`:

```kotlin
private fun sendTroops() {
    tapLocation(540, 1100)  // Adjust for your screen
}
```

## 🏗️ Architecture

```
┌─────────────────────────────────────────┐
│  GatheringAccessibilityService          │
│  (Main orchestrator & UI)               │
├─────────────────────────────────────────┤
│  ScreenAnalyzer          AutoGatheringEngine
│  • Color detection       • Target selection
│  • Badge finding         • Gesture control
│  • Level estimation      • Troop dispatch
└─────────────────────────────────────────┘
```

### Key Components

**ScreenAnalyzer.kt**
- Detects RSS tile badges using pixel analysis
- Identifies resource type by color matching
- Estimates level from badge characteristics
- Detects occupancy and calculates confidence

**AutoGatheringEngine.kt**
- Manages gathering cycle (every 8 seconds)
- Selects best target based on priority
- Executes troop dispatch gestures
- Handles confirmation dialogs

**GatheringAccessibilityService.kt**
- Main service managing lifecycle
- Screenshot capture and processing
- Screen analysis coordination
- UI overlay and status updates

## 📊 Detection Example

```
Scan #5
Analyzing screen...
Found 12 targets:
1. Gold L5 (95%) 
2. Emerging L4 (88%)
3. Ore L3 (82%)
4. Wood L2 ⛔ (75%)
5. Gold L1 (92%)
...
```

## ⚙️ Tuning Detection

### Tiles Not Detected?
1. Run **🔍 ONE SCAN** to verify
2. Reduce `minimumConfidence` (lower = more detections)
3. Update `RESOURCE_COLORS` for your game version
4. Clear overlapping UI elements

### False Positives?
1. Increase `minimumConfidence` threshold
2. Tighten color ranges in `RESOURCE_COLORS`
3. Adjust badge size detection limits

### See [SETUP_GUIDE.md](SETUP_GUIDE.md) for detailed tuning guide

## 🔐 Safety Features

- ✅ No map movement (tile taps only)
- ✅ Selectable troop types
- ✅ Occupancy filtering
- ✅ Manual emergency stop
- ✅ Gesture-based (no input injection)
- ✅ Accessibility service standard

## 📝 Changelog

### V12 (Current)
- Full auto-gathering system
- ScreenAnalyzer tile detection
- AutoGatheringEngine integration
- Real-time status overlay
- Customizable resource priority

### V11
- Initial scanner framework
- Manual tile detection

### V10
- Basic accessibility service

## 🤝 Contributing

Improvements welcome! Areas for enhancement:
- ML-based tile detection
- Strategy AI improvements
- Multi-castle support
- Better color calibration
- Performance optimization

## ⚠️ Legal Disclaimer

This tool is for **personal, private use only**. Automated gathering may violate Lords Mobile's Terms of Service. Users assume all responsibility for compliance with game rules and policies.

## 📞 Support

For help:
1. Check [SETUP_GUIDE.md](SETUP_GUIDE.md) Troubleshooting section
2. Review logcat output: `adb logcat | grep Lords`
3. Test with **🔍 ONE SCAN** first
4. Gradually adjust configuration parameters

## 📂 Project Structure

```
lords-gathering-assistant/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/coolhimanhub/lordsgathering/
│   │       │   ├── GatheringAccessibilityService.kt
│   │       │   ├── ScreenAnalyzer.kt
│   │       │   ├── AutoGatheringEngine.kt
│   │       │   └── MainActivity.kt
│   │       ├── AndroidManifest.xml
│   │       └── res/
│   ├── build.gradle
│   └── ...
├── build.gradle
├── settings.gradle
├── SETUP_GUIDE.md
└── README.md
```

## 🎓 How It Works

1. **Screenshot Capture** - Takes game screen via accessibility API
2. **Tile Analysis** - ScreenAnalyzer detects resource badges by color
3. **Target Selection** - AutoGatheringEngine picks best tile by priority
4. **Troop Dispatch** - Executes tap gestures to send troops
5. **Repeat Cycle** - Waits 8 seconds and repeats

## 🚀 Future Roadmap

- [ ] Machine learning tile detection
- [ ] Pathfinding for optimized gathering
- [ ] Multi-castle simultaneous gathering
- [ ] Farming strategy profiles
- [ ] Healing and defense automation
- [ ] Event farming templates
- [ ] Data analytics dashboard

---

**Version:** V12  
**Last Updated:** 2026-09-10  
**Status:** Production Ready

Made with ⚔️ for Lords Mobile players
