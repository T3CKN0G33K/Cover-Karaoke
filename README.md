# Cover Karaoke 🎤

A concurrent cover screen media & synchronized lyrics app optimized for **Samsung Galaxy Z Fold 8 & Z Fold 8 Ultra**, powered by **Shizuku** and Android's **Presentation API** (Display ID 1).

![Cover Karaoke](app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp)

---

## 🌟 Key Features

### 🎶 Synchronized Lyrics & Karaoke Engine
- **Enhanced LRC Syllable Support:** Recognizes `<mm:ss.xx> word <mm:ss.xx>` word-timestamp tags and smoothly wipes color across individual words as playback advances.
- **Apple Music Focus & Blur Physics:** Active lyric line scales to `1.15x` (pure white, bold), while non-active rows scale to `0.88x` with a subtle `RenderEffect` blur (API 31+).
- **Center-Lock Auto Scroll:** Active lyric lines smoothly center in the vertical middle of the cover screen.
- **Tap-to-Seek:** Tap any previous or upcoming lyric line to jump music playback directly to that timestamp.

### 🎨 Spotify Theme & Dynamic Backdrop
- **Palette-Based Ambient Backdrop:** Displays a blurred album art background combined with a 3-stop mesh gradient extracted from the song's artwork.
- **Pure Black AMOLED Mode:** Toggle pure `#000000` pitch-black background to maximize OLED power efficiency while keeping text and artwork floating.

### 🕰️ Desk Ambient HUD & Morphing Status Pod
- **Double-Tap / Swipe Mode Switcher:** Double-tap or swipe to toggle between **Karaoke Deck** and **Desk Ambient HUD**.
- **Desk View:** Large 56sp digital clock, full date, live battery %, and bottom mini Now Playing pill.
- **Unified Status Pod (`UnifiedStatusView`):** Custom Canvas status widget featuring an open battery crescent arc, Wi-Fi arches, and curved cellular signal dots.
- **Live System State Manager (`SystemStatusManager`):** Real-time binding for battery %, charging state, Wi-Fi RSSI, and cellular signal level.

### 🔄 Tent Mode Auto-Rotation (180° Flip)
- **Sensor Orientation Tracking:** Automatically detects when the foldable device is placed upside-down in tent mode (keeping the USB-C charging port accessible at top) and rotates the UI 180°.

### 🫧 Floating Action Overlay (`FloatingBubbleService`)
- **System Overlay Bubble:** Renders over Spotify, YouTube, and other apps.
- **Short Tap:** Toggles the cover screen display on/off.
- **Long Press:** Opens a compact quick-settings card with a **Cover Brightness Slider**, **Pure Black AMOLED Switch**, and **Display Sleep/Wake Toggle**.

---

## 🚀 Setup & Requirements

1. **Permissions:**
   - Grant **Notification Access** (`CoverNotificationListener`) when prompted.
   - Grant **Display Overlay** (`SYSTEM_ALERT_WINDOW`) permission.
2. **Shizuku Service:** Ensure Shizuku is running and authorized for `Cover Host`.
3. **Media Players:** Works automatically with **Spotify**, **YouTube**, **Apple Music**, and any app publishing standard `MediaSession` metadata.

---

## 🛠️ Tech Stack & Architecture

- **Language:** Kotlin
- **UI Toolkit:** Android Canvas API, View Property Animators, `RenderEffect` Shader Blurs, Palette API
- **Media System:** `MediaSessionManager`, `MediaController`, LRCLIB API
- **IPC / Hardware:** Shizuku Binder Shell, `DisplayManager`, `Presentation` API, `OrientationEventListener`
