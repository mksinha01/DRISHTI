# Drishti (दृष्टि) — AI Voice Companion for Visually Impaired

> **A Voice-First, Multi-Modal Mobility & Assistive System**  
> Built as an Android Native (Kotlin) app paired with LiveKit Voice Agent, on-device TensorFlow Lite vision, and optional ESP32-CAM Wi-Fi streaming.

---

## 1. Mission Statement

Drishti provides visually impaired individuals with complete auditory independence. Operating 24/7 as an Android foreground service with earphone audio interaction, it offers three primary modes switched entirely by voice:

- **Assistive Voice Agent**: Continuous background obstacle detection, scene Q&A ("What is in front of me?"), everyday queries, and emergency protection.
- **Navigation Mode**: Turn-by-turn walking guidance, heading tracking, and obstacle-aware pathway alerts.
- **OCR / Reading Mode**: Text recognition from camera frames for reading printed signs, labels, currency, and documents, with conversational summarization.

---

## 2. System Architecture

```
┌─────────────────┐       HTTP MJPEG       ┌──────────────────────────────────────────────┐
│    ESP32-CAM    │ ─────────────────────> │             Android App (Kotlin)             │
│ (WiFi Hotspot)  │                        │                                              │
└─────────────────┘                        │  - CameraManager (ESP32 / Phone CameraX)     │
                                           │  - TFLiteDetector (YOLOv8n Obstacles)        │
┌─────────────────┐       CameraX          │  - LocalTTS (Offline Priority Safety Alerts) │
│  Phone Camera   │ ─────────────────────> │  - FallDetector & LocationProvider           │
└─────────────────┘                        │  - ModeManager (State Machine)               │
                                           │  - LiveKitClient (WebRTC Audio/Data Channel) │
┌─────────────────┐                        └──────────────────────┬───────────────────────┘
│   Earphone      │ <── Audio In / Out ───────────────────────────┤
│   Mic / Speaker │                                               │
└─────────────────┘                                               │ WebRTC Voice & Data
                                                                  ▼
                                           ┌──────────────────────────────────────────────┐
                                           │               LiveKit Cloud                  │
                                           └──────────────────────┬───────────────────────┘
                                                                  │
                                                                  ▼
┌─────────────────┐                        ┌──────────────────────────────────────────────┐
│  Token Server   │ ── Signed JWTs ──────> │             LiveKit Agent Worker             │
│ (FastAPI:8000)  │                        │                                              │
└─────────────────┘                        │  - STT: Sarvam Saaras v3 / Whisper           │
                                           │  - LLM: OpenAI GPT-4o / Gemini 2.0           │
                                           │  - TTS: Sarvam Bulbul v3 / Silero            │
                                           │  - Data Channel: OCR text & Obstacle events  │
                                           └──────────────────────────────────────────────┘
```

---

## 3. Repository Structure

```
vision_man/
├── android/                             # 📱 Native Android Application (Kotlin)
│   ├── app/
│   │   ├── src/main/java/ai/drishti/
│   │   │   ├── MainActivity.kt          # UI entrypoint, accessible controls, permissions
│   │   │   ├── DrishtiService.kt        # Foreground service orchestrating all modules
│   │   │   ├── audio/
│   │   │   │   ├── LiveKitClient.kt     # WebRTC room audio/video & data channel
│   │   │   │   └── LocalTTS.kt          # Low-latency offline Android TextToSpeech
│   │   │   ├── camera/
│   │   │   │   ├── CameraManager.kt     # Coordinates ESP32-CAM ↔ Phone Camera fallback
│   │   │   │   ├── Esp32StreamClient.kt # HTTP MJPEG parser & bitmap decoder
│   │   │   │   └── PhoneCamera.kt       # CameraX integration for phone camera
│   │   │   ├── vision/
│   │   │   │   ├── TFLiteDetector.kt    # On-device YOLOv8n TFLite inference
│   │   │   │   ├── ObstacleClassifier.kt# Proximity & position calculation
│   │   │   │   ├── FaceDetector.kt      # Privacy-preserving ML Kit face detector
│   │   │   │   └── OcrEngine.kt         # On-device ML Kit text recognition
│   │   │   ├── navigation/
│   │   │   │   ├── NavigationEngine.kt  # Route coordination & turn alert generation
│   │   │   │   └── NavStateManager.kt   # Waypoint progression & off-route detection
│   │   │   ├── mode/
│   │   │   │   ├── ModeManager.kt       # Type-safe sealed class state machine
│   │   │   │   ├── VoiceIntentRouter.kt # Natural voice intent classification
│   │   │   │   └── ModeConfig.kt        # Per-mode parameter presets
│   │   │   ├── emergency/
│   │   │   │   ├── EmergencyMonitor.kt  # Cancellation window & countdown state machine
│   │   │   │   ├── AlertSender.kt       # Multi-channel dispatch (SMS, WhatsApp, Call)
│   │   │   │   └── ContactStore.kt      # Encrypted local contact storage
│   │   │   ├── sensors/
│   │   │   │   ├── LocationProvider.kt  # FusedLocationProviderClient GPS wrapper
│   │   │   │   └── FallDetector.kt      # Accelerometer fall impact heuristic
│   │   │   └── utils/
│   │   │       ├── PreferenceManager.kt # Device & network preferences
│   │   │       └── WifiProvisioner.kt   # SoftAP/BLE Wi-Fi provisioner for ESP32
│   │   ├── src/test/java/ai/drishti/    # JVM Unit Tests
│   │   │   ├── ModeManagerTest.kt
│   │   │   ├── VoiceIntentRouterTest.kt
│   │   │   ├── ObstacleClassifierTest.kt
│   │   │   ├── NavStateManagerTest.kt
│   │   │   └── EmergencyMonitorTest.kt
│   │   └── src/main/res/                # Layouts, themes, strings, accessibility
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── agent/                               # 🔧 LiveKit Agent Worker (Python)
│   ├── drishti_agent.py                 # Multi-modal voice agent with mode awareness
│   ├── requirements.txt                 # Pinned dependencies
│   ├── .env.example                     # Environment template
│   └── tests/
│       └── test_drishti_agent.py        # Agent unit tests
│
├── token-server/                        # 🔐 FastAPI Token Authentication Server
│   ├── main.py                          # LiveKit JWT generation & health check
│   ├── requirements.txt                 # Dependencies
│   ├── .env.example                     # Environment template
│   └── tests/
│       └── test_token_server.py         # Token server unit tests
│
├── esp32-firmware/                      # 🔌 ESP32-CAM Firmware
│   ├── mjpeg_streamer/
│   │   ├── mjpeg_streamer.ino           # Arduino MJPEG camera server
│   │   └── config.h                     # Pin definitions & Wi-Fi config
│   └── README.md                        # Flashing guide
│
├── assets/
│   ├── labels/coco_labels.txt           # 80 COCO classes for YOLO
│   └── models/README.md                 # TFLite model instructions
│
├── docs/                                # Architecture & design specifications
│   ├── architecture_overview.md
│   ├── mode_manager_design.md
│   └── emergency_alert_flow.md
│
├── start_all.bat                        # Unified local startup script
└── README.md
```

---

## 4. Voice Commands & Mode Switching

All actions can be triggered by voice through the connected earphone mic:

| User Utterance | Resulting Action |
|---|---|
| `"Switch to navigation"` | Switches to Navigation mode |
| `"Take me to the railway station"` | Enters Navigation mode and plans walking route |
| `"Switch to read mode"` / `"Read this"` | Triggers camera OCR and reads text aloud |
| `"Summarize this"` | Reads a concise summary of the captured text |
| `"Switch to assistant"` | Returns to default companion mode |
| `"Which mode am I in?"` | Spoken announcement of current mode |
| `"Help, emergency!"` | Initiates 10-second emergency countdown |
| `"Cancel"` / `"Stop"` | Cancels emergency countdown |

---

## 5. Coding Principles Adherence

This codebase strictly follows clean software engineering best practices:
1. **KISS (Keep It Simple, Stupid)**: Straightforward classes with clear, readable logic.
2. **DRY (Don't Repeat Yourself)**: Shared utilities for distance calculations, coordinates, and network calls.
3. **YAGNI (You Aren't Going to Need It)**: Built strictly to the requirements of the architecture plan.
4. **Meaningful Naming**: Clean, self-explanatory domain models (`ObstacleClassifier`, `VoiceIntentRouter`, `EmergencyMonitor`, `DrishtiService`).
5. **Single Responsibility Principle (SRP)**: Every component has exactly one well-defined responsibility.
6. **Proper Error Handling**: Graceful network and camera fallbacks; no swallowed exceptions.
7. **Write Tests**: Fully covered with unit test suites across Python and Kotlin modules.

---

## 6. Getting Started

### 1. Token Server
```bash
cd token-server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

### 2. LiveKit Agent Worker
```bash
cd agent
pip install -r requirements.txt
python drishti_agent.py start
```

### 3. Run All Backend Services (Windows)
Double-click or run:
```powershell
.\start_all.bat
```

### 4. Android App
Open the `android/` directory in Android Studio and run on a connected device or emulator.
Ensure the phone has microphone, camera, and location permissions granted.
