# PRD: Drishti — AI Voice Companion for the Visually Impaired

**Problem Statement:** PS-05 — AI-Powered Assistive System for Visually Impaired People
**Type:** Hackathon MVP (target build window: ~12 hours), with a phased path to a real product
**Status:** Draft v3 — final, incorporating feasibility review + production roadmap

---

## 0. Summary of Decisions

- **Architecture:** ESP32-CAM → WiFi → Laptop (YOLOv8n + face_recognition) → audio output. LiveKit voice agent (STT→LLM→TTS) is a **gated stretch layer**, not the guaranteed path — a fully local, internet-free stack is the non-negotiable fallback.
- **Modes:** Navigation (proactive obstacle/hazard alerts) + Assistant (on-demand conversational Q&A, includes face recognition), switched via a physical button (primary) or voice command (secondary).
- **Known-people repo check:** an existing repo (`agent-starter-embed`) was reviewed and found to use Pipecat + Daily.co, not LiveKit. Decision: keep LiveKit as planned; the repo is not part of this build, but stays available as a proven fallback voice stack if LiveKit setup stalls.
- **Feasibility review outcome:** several v1 assumptions were corrected — ESP32-CAM needs an external FTDI programmer (no onboard USB), GPIO pin choice for the mode button matters (avoid GPIO16/0/4 and SD-card pins), `face_recognition`/dlib is officially Mac/Linux only and often fails to `pip install` on Windows, YOLOv8 is AGPL-3.0 licensed, and LiveKit's real-world latency is commonly 1.4–1.7s, not sub-second. All of this is reflected in the plan below.
- **Post-hackathon direction:** this PRD explicitly treats the hackathon build as a validated MVP, not a production system, and lays out a 4-phase path to an eventual mobile product.

---

## 1. Overview

Drishti is a portable assistive system pairing a low-cost camera node (ESP32-CAM, or a laptop webcam as fallback) with local computer vision (YOLOv8n + face_recognition) and audio output. A **local-only pipeline is the guaranteed base**; a LiveKit-powered conversational voice layer is a gated stretch enhancement added only if early proof-of-concept tests succeed.

## 2. Problem Statement

Visually impaired individuals face two related challenges navigating unfamiliar environments:

1. **Reactive hazard awareness** — knowing what's physically in their path before contact.
2. **Situational understanding** — knowing who/what is around them, on demand.

Drishti addresses both using local CV, with an optional conversational layer.

## 3. Target User

- Visually impaired individuals navigating semi-familiar indoor/outdoor environments.
- Secondary: caregivers who may pre-register known faces.

## 4. Goals & Success Metrics (hackathon demo)

| Goal | Success looks like |
| --- | --- |
| Real-time obstacle awareness | System announces a new close obstacle/person within a few seconds — exact number set only after POC 1 timing, not assumed upfront |
| Known-person recognition | Correctly distinguishes 2-3 pre-registered faces from strangers under actual demo lighting (POC 3) |
| Usable mode switching | Button press reliably cycles mode with spoken confirmation |
| Guaranteed live demo | The **local-only stack** runs live without any internet dependency, as the non-negotiable fallback |
| Stretch: conversational Q&A | If POC 2 succeeds, user can ask a question and get a spoken answer via LiveKit |

## 5. Scope

### MUST BUILD (local-only, no internet dependency)

- ESP32-CAM → WiFi → laptop frame pipeline (fallback: laptop webcam directly, if POC 1 fails)
- YOLOv8n (COCO pretrained) for obstacles/objects/people, running locally on CPU
- Near / medium / far proximity from bounding-box size (not literal "steps" — unverified calibration was dropped)
- Local TTS (pyttsx3/gTTS) — no cloud dependency
- Physical push-button mode switch (Navigation ↔ Assistant), wired to a **non-shared GPIO pin** (13, 14, or 15 — avoid GPIO16, GPIO0, GPIO4, and SD-card pins)

### SHOULD BUILD — gated stretch layer (only after MUST BUILD is stable)

- LiveKit Agents voice pipeline (STT→LLM→TTS) for conversational Assistant-mode Q&A — **gated behind POC 2**
- Face recognition (`face_recognition`/dlib) for known vs. unknown people — **gated behind POC 3 and an install test on the actual dev machine's OS**
- Voice-command mode switching as a secondary input to the button

### Explicitly out of scope (12-hour window)

- Sign/text reading (OCR)
- True on-device inference (removing the laptop dependency) — see Phase 2 roadmap instead
- Haptic feedback
- Precise distance-in-steps calibration
- Multi-person tracking/re-identification
- Any production hardening (auth, scaling, monitoring, privacy compliance)

## 6. System Architecture

```mermaid
flowchart LR
    A[ESP32-CAM or laptop webcam] -- frames --> B[Laptop: Vision Pipeline]
    B --> C[YOLOv8n: objects/obstacles/people]
    B -.optional, gated.-> D[face_recognition: known vs unknown]
    C --> E[Context Builder]
    D -.--> E
    E --> F[Local TTS: pyttsx3/gTTS]
    F --> G[Speaker/Earphone]
    E -.optional, gated.-> H[LiveKit Voice Agent]
    H -.--> I[STT: user voice query, needs mic]
    H -.--> J[LLM: composes response from context]
    H -.--> K[TTS: spoken output]
    K -.--> G
    L[Push Button on ESP32, safe GPIO pin] -- mode signal --> B
    B -- mode state --> F
    B -.--> H
```

Solid lines = MUST BUILD path (fully local, no internet). Dotted lines = SHOULD BUILD stretch layer, gated behind POCs 2 and 3.

**Flow (MUST BUILD path):**

1. Camera (ESP32-CAM or webcam fallback) sends frames to laptop.
2. YOLOv8n detects objects/obstacles/people; context builder tracks proximity (near/med/far) and position (left/center/right).
3. In Navigation mode, new/close detections trigger a short local TTS announcement, with a cooldown to avoid repetition.
4. Button press toggles Navigation ↔ Assistant mode with spoken confirmation.

**Flow (SHOULD BUILD stretch, if POCs pass):**
5. In Assistant mode, the laptop mic captures a spoken question; LiveKit's STT→LLM→TTS composes an answer using the live detection context, including known/unknown face identity if face recognition passed its POC.

## 7. Feature Details by Mode

### Navigation Mode (default, MUST BUILD)

- Continuously scans frames; announces new obstacles/people via local TTS, prioritized by proximity.
- Cooldown logic prevents re-announcing the same object unless proximity changes materially.
- Example output: *"Chair ahead, close."* / *"Person approaching, left."*

### Assistant Mode (Q&A, SHOULD BUILD — gated)

- User asks a question via voice (requires a working microphone — external USB mic recommended if the demo hall is loud).
- LiveKit STT→LLM→TTS composes an answer from live detection context.
- Known-face names surface here if face recognition's POC passed; otherwise generic "a person is here."

### Mode Switching

- **Primary:** physical push-button on a confirmed-safe ESP32 GPIO pin — single press cycles mode, spoken confirmation.
- **Secondary (stretch):** voice command via LiveKit STT, only if the Assistant-mode layer is built.

## 8. Tech Stack

| Layer | Choice | Note |
| --- | --- | --- |
| Capture hardware | ESP32-CAM, fallback: laptop webcam | Requires a separate FTDI/USB-TTL programmer to flash — no built-in USB port |
| Comms | WiFi | Verify achievable fps on venue network in POC 1 before committing |
| Object/obstacle detection | YOLOv8n (Ultralytics), COCO pretrained | AGPL-3.0 licensed — fine for a hackathon demo; a paid Enterprise license would be needed to embed it in a closed-source commercial product later |
| Face recognition (stretch) | `face_recognition` (dlib) | Officially Mac/Linux only; Windows install is unofficial and dlib's pip compile frequently fails without cmake/build tools preinstalled — test on the actual dev machine first |
| Local audio output (MUST BUILD) | pyttsx3 or gTTS | No internet dependency, no API cost |
| Voice agent (stretch) | LiveKit Agents (STT+LLM+TTS) | Requires a LiveKit account/server plus separate STT/LLM/TTS provider API keys (or LiveKit Cloud's bundled inference); always internet-dependent; published production latency commonly 1.4–1.7s round trip |
| Voice agent — proven fallback reference | Pipecat + Daily.co (`agent-starter-embed` repo) | Not used in this build; kept on hand as a tested fallback if LiveKit setup stalls, since it's a working template the team has run before |
| Glue/backend | Python | — |

## 9. Hardware Checklist

- ESP32-CAM module
- FTDI/USB-TTL programmer or ESP32-CAM-MB board — required to flash the ESP32-CAM
- Push button wired to a confirmed-safe GPIO pin (13/14/15, not 16/0/4, and not SD-card pins)
- Portable power bank for ESP32
- Laptop (runs YOLOv8n, optionally face_recognition and the LiveKit agent)
- Working microphone (built-in or external USB) — required for Assistant-mode voice queries
- Speaker/earphone for audio output
- Mounting rig (clip/lanyard) for demo portability

## 10. Proof-of-Concept Gate (run these FIRST)

| POC | Hypothesis | Test | Success | If it fails |
| --- | --- | --- | --- | --- |
| 1. Capture pipeline | ESP32-CAM streams usable frames over venue WiFi | Run stock CameraWebServer example on-site for 5 min | ≥5fps, no repeated disconnects | Use laptop webcam as the capture source instead |
| 2. Voice pipeline latency | LiveKit STT→LLM→TTS completes in a usable time | Build minimal LiveKit "hello world" agent, time 10 round trips | Consistent, acceptable latency for a live demo | Drop LiveKit; use local TTS only, no conversational Q&A (or fall back to the Pipecat/Daily.co repo if time allows) |
| 3. Face recognition accuracy | 3 known faces distinguishable from strangers | Register 3 faces, test on 10 photos in demo lighting | ≥8/10 correct | Drop identity claims; keep generic "person detected" from YOLO |

## 11. 12-Hour Build Timeline

| Time | Milestone |
| --- | --- |
| 0–1h | Run POC 1 (capture) and POC 2 (voice latency) in parallel if team splits; decide fallback paths immediately |
| 1–3h | Local pipeline: camera → YOLOv8n → proximity classification working |
| 3–4h | Local TTS wired to detections; Navigation mode cooldown/anti-spam logic |
| 4–5h | Button wired to a confirmed-safe GPIO pin; mode-switch logic + spoken confirmation |
| 5–6h | **Decision checkpoint:** is the local-only MUST BUILD stack demo-ready? If yes, this is your guaranteed fallback — protect it from further changes |
| 6–7h | Run POC 3 (face recognition); only proceed if it passes |
| 7–9h | If POC 2 passed: build LiveKit Assistant-mode Q&A on top of the working local stack |
| 9–10h | Integrate face recognition into Assistant-mode context, if POC 3 passed |
| 10–11h | Voice-command mode switching (stretch), only if time remains |
| 11–12h | End-to-end rehearsal on the local-only fallback path first, then the full stack |

## 12. Critical Risks & Fallbacks

```
LiveKit voice agent           →  if setup/latency fails  →  Local TTS only, canned phrases (or Pipecat/Daily.co repo)
face_recognition (dlib)       →  if install fails          →  Generic "person detected" (from YOLO)
ESP32-CAM WiFi streaming      →  if unstable at venue       →  Laptop webcam as capture source
Venue internet (for LiveKit)  →  if unavailable             →  Full local-only stack, zero cloud dependency
```

**Principle:** the demo must never depend on a single fragile, internet-dependent component.

## 13. Risks & Mitigations

| Risk | Mitigation |
| --- | --- |
| WiFi latency/instability between ESP32 and laptop | Same local hotspot; reduce resolution/frame rate; webcam fallback |
| LiveKit + LLM latency/setup overrun for a team without prior LiveKit experience | Gate behind POC 2; treat as stretch, not core; Pipecat/Daily.co repo as a tested backup |
| dlib/face_recognition install failure, especially on Windows | Test on actual dev machine before committing; generic-person fallback |
| No FTDI programmer on hand | Acquire before the hackathon — no in-hackathon fallback for this |
| Time overrun on integration | Build and test each piece independently (POCs); keep local-only fallback always demoable |

## 14. Path to a Real Product (post-hackathon)

The hackathon build is a **validated MVP, not a production system**. Presenting it this way — with an explicit roadmap — is also the stronger pitch framing, since claiming "production ready" invites scrutiny the hackathon build can't yet survive.

### Phase 1 — Hackathon MVP (this PRD)

ESP32-CAM + laptop brain, local-only fallback guaranteed, LiveKit as a gated stretch. Goal: prove the concept works, not ship it.

### Phase 2 — Mobile prototype

- Re-export YOLOv8n to TFLite (Android) / CoreML (iOS) and validate real on-device inference speed on actual phones, not just laptop CPU
- Replace `face_recognition`/dlib entirely — it does not run on mobile — with a mobile-compatible pipeline (e.g. Google ML Kit face detection + a TFLite embedding model such as MobileFaceNet)
- Replace local TTS with native platform TTS (Android TextToSpeech / iOS AVSpeechSynthesizer)
- If keeping the ESP32-CAM, build a real WiFi provisioning flow (BLE setup or captive-portal AP mode) instead of a hardcoded SSID/password — or drop the ESP32 in favor of the phone's own camera
- LiveKit's mobile SDKs (Swift/Kotlin/Flutter) are a genuine, supported swap for the laptop-based agent client at this stage

### Phase 3 — Pilot with real users

- Test with actual visually impaired users in real environments over weeks, not a demo hall
- Collect structured feedback on false negatives/positives — a missed obstacle here is a safety issue, not a bug ticket
- Gather real battery, comfort, and reliability data from daily use

### Phase 4 — Production

- Only after Phase 3 feedback is addressed
- Privacy/data handling review for camera footage and face data (of both the user and bystanders)
- Reliability hardening, support processes, and an honest look at what regulatory or duty-of-care expectations apply to a safety-relevant assistive device in your target market
- iOS background execution restrictions and battery/thermal limits need real engineering solutions, not just "it runs" in a demo

## 15. Licensing Notes

- YOLOv8 (Ultralytics): AGPL-3.0. Fine for a hackathon demo; a commercial closed-source product would need an Ultralytics Enterprise license.
- `face_recognition`: MIT, but depends on dlib (Boost Software License) — the licensing isn't the blocker, the Windows install friction is.
- LiveKit server: Apache 2.0.
