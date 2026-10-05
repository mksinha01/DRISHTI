# Mode Manager Design

The Drishti system operates under a clear, state-machine-driven mode paradigm designed around voice-only interaction:

## Modes

1. **ASSISTANT (Default Mode)**
   - Continuous background obstacle monitoring.
   - Conversational scene Q&A ("What is in front of me?", "Who is standing here?").
   - Everyday tasks: reminders, clock, battery status, emergency contact queries.

2. **NAVIGATION Mode**
   - Triggered by "Switch to navigation" or "Take me to [Destination]".
   - Integrates GPS tracking, compass heading, and waypoint guidance.
   - Provides proactive turn announcements ("In 20 meters, turn right on 5th Avenue").
   - Interweaves real-time obstacle warnings on the user's path.

3. **OCR / READING Mode**
   - Triggered by "Switch to read mode" or "Read this".
   - High-resolution frame capture sent to ML Kit OCR for on-device text extraction.
   - Allows conversational follow-up: "Read again", "Read slower", "Summarize", or "Translate to Hindi".

4. **EMERGENCY Mode**
   - Triggered by voice ("Help emergency"), physical fall detection, or prolonged inactivity.
   - Enters a cancelable countdown window (10s voice, 15s fall).
   - If not cancelled, automatically dispatches SMS with Google Maps coordinates and initiates phone dialer.

## State Transitions
Transitions between states are executed via Kotlin sealed classes (`DrishtiMode`), ensuring type safety and deterministic event handling.
Spoken confirmations accompany every transition so the user always knows the active operating context.
