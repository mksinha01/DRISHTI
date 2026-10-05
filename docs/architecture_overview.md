# Drishti Architecture Overview

Drishti is a voice-first assistive AI companion designed for visually impaired users. It runs on an Android device worn with an earphone and microphone, providing obstacle awareness, turn-by-turn navigation, and on-demand OCR.

## System Topology

```
[ ESP32-CAM (WiFi) ] ──┐
                       ├──> [ Android App (Kotlin) ] <──> [ LiveKit Cloud ] <──> [ Agent Worker (Python) ]
[ Phone CameraX ]    ──┘           │                                                     │
                                   ▼                                                     ▼
                             Local Vision (TFLite)                               Cloud Vision (GPT-4o/Gemini)
                             Local TTS Fallback                                  Google Maps Routes API
                             Emergency Monitor                                   Sarvam STT / TTS
```

## Core Responsibilities

1. **Android Application (`android/`)**:
   - Background foreground service (`DrishtiService`) running 24/7.
   - Dual-source camera manager (`CameraManager`) switching seamlessly between ESP32-CAM and Phone CameraX.
   - Local TFLite detector (`TFLiteDetector` + `ObstacleClassifier`) processing frames at ~5-10 FPS for immediate priority warnings.
   - Low-latency local TTS (`LocalTTS`) for obstacle and safety alerts.
   - Sensor monitor (`FallDetector` + `EmergencyMonitor`) for fall detection and voice emergency triggers.
   - Navigation tracking (`NavigationEngine` + `NavStateManager`) using GPS and bearing.

2. **Cloud Agent Worker (`agent/`)**:
   - Connects to LiveKit room as an AI participant.
   - High-quality STT and natural Indian-accented TTS (Sarvam AI / Silero / OpenAI).
   - Mode-aware conversational assistant responding to scene questions, navigation requests, and document reading.
   - Vision-LLM integration for detailed scene description and Q&A.

3. **Token Server (`token-server/`)**:
   - Fast, stateless FastAPI server issuing signed LiveKit JWT tokens for client connection authentication.
