# HACKATHON MASTER DEVELOPMENT PROMPT
## AI Assistant for Blind People — Parallel 3-Developer Implementation

We are currently in a hackathon and need to complete the existing project TODAY.

The project is an AI-powered assistant for blind/visually impaired users.

The assistant should use the camera to understand the environment, identify people and objects, read text, assist with navigation, and communicate with the user through realtime voice.

IMPORTANT:
This is NOT a request to rebuild the project from scratch.

First inspect the EXISTING PROJECT and understand what is already implemented.

Our rule is:

> KEEP ALL WORKING FEATURES WORKING.
> IMPLEMENT ONLY MISSING FEATURES OR IMPROVE EXISTING FEATURES.
> DO NOT randomly rewrite working architecture.

---

# 1. FIRST TASK — UNDERSTAND THE EXISTING PROJECT

Before changing code:

1. Inspect the entire repository.
2. Identify:
   - frontend
   - backend
   - AI/LLM layer
   - agent/router
   - camera/video pipeline
   - voice input
   - voice output
   - OCR
   - object detection
   - person recognition
   - navigation
   - database/storage
   - APIs
   - configuration
   - environment variables
3. Run the existing application.
4. Test existing functionality.
5. Create a short CURRENT STATUS report:

| Feature | Status | Working? | File/Module | Missing |
|---|---|---|---|---|
| Voice assistant | | | | |
| LLM | | | | |
| Agent routing | | | | |
| Camera | | | | |
| Person detection | | | | |
| Person recognition | | | | |
| Person memory | | | | |
| OCR | | | | |
| Navigation | | | | |
| Realtime conversation | | | | |
| Object detection | | | | |

Do not modify code during this inspection phase unless required to run it.

---

# 2. PRODUCT GOAL

The final demo should feel like a real assistant for a blind person.

Example interaction:

User:

"Hey assistant."

Assistant:

"Yes?"

User:

"What is in front of me?"

Camera sees:

- person
- chair
- door
- text

Assistant should respond:

"There is a person about two meters in front of you. There is a chair slightly to your right and a door ahead."

The system should NOT continuously describe everything.

It should intelligently decide what information is relevant.

---

# 3. CORE ARCHITECTURE

Use an agent/router architecture.

Conceptually:

Camera
   ↓
Vision Processing
   ↓
Scene Understanding
   ↓
Event / Intent Detection
   ↓
Agent Router
   ↓
Specialized Agent
   ├── General Assistant Agent
   ├── Person Recognition Agent
   ├── OCR Agent
   ├── Navigation Agent
   ├── Object/Obstacle Agent
   └── Conversation Agent
   ↓
Response Generator
   ↓
TTS
   ↓
User

The LLM should NOT process every camera frame.

Use deterministic/vision models first.

Example:

Camera frame
→ object/person detection
→ OCR only when needed
→ face recognition only when person detected
→ navigation processing only when navigation mode is active
→ LLM receives structured information
→ LLM generates natural response

This reduces latency and API cost.

---

# 4. AGENT ROUTING

Implement or improve a central Agent Router.

The router should determine which agent should handle a request.

Example:

User:
"Read this text."

→ OCR Agent

User:
"Who is this?"

→ Person Recognition Agent

User:
"Save this person as Rahul."

→ Person Memory Agent

User:
"Where should I go?"

→ Navigation Agent

User:
"What is around me?"

→ Scene Understanding Agent

User:
"Talk to me."

→ Conversation Agent

User:
"Is there anything in front of me?"

→ Obstacle/Object Detection Agent

The router should return structured routing information.

Example:

{
  "intent": "person_identification",
  "agent": "person_agent",
  "requires_camera": true,
  "requires_llm": false
}

Do NOT use the LLM when a deterministic route is sufficient.

---

# 5. PERSON IDENTIFICATION FEATURE

This is a critical hackathon feature.

Required interaction:

User sees a person.

User says:

"Save this person as Manoj."

System:

1. Capture the current camera frame.
2. Detect the person's face.
3. Generate a face embedding.
4. Store the embedding.
5. Store:

{
    "name": "Manoj",
    "embedding": [...],
    "created_at": "...",
    "metadata": {}
}

Later:

Camera sees the same person.

System:

1. Detect face.
2. Generate embedding.
3. Compare against stored embeddings.
4. If similarity passes threshold:
   
"Manoj is in front of you."

If unknown:

"There is an unknown person in front of you."

IMPORTANT:

Do not identify people using image captioning alone.

Use a proper face recognition pipeline:

Face Detection
→ Face Alignment
→ Face Embedding
→ Vector Similarity
→ Identity

Use the existing project model if already implemented.

If no model exists, evaluate a lightweight suitable face recognition model such as:

- ArcFace
- InsightFace

Prefer a model that can run locally/at the edge.

---

# 6. PERSON MEMORY

Create a clean interface:

save_person(name, face_embedding)

recognize_person(face_embedding)

list_people()

delete_person(name)

The storage implementation should be isolated.

Do NOT spread person database logic across the application.

Use a clear service:

services/person_memory/

or equivalent existing architecture.

---

# 7. REALTIME PERSON CONVERSATION

We need to understand:

"What happens if a person is in front of the camera and the user wants to talk about that person?"

Example:

User:

"Who is in front of me?"

Vision:

Face detected.

Recognition:

Manoj.

Assistant:

"Manoj is in front of you."

User:

"What is he doing?"

Camera/vision:

Manoj is standing.

Assistant:

"Manoj appears to be standing in front of you."

Important:

Do NOT send every video frame to the LLM.

Use:

Realtime video
→ frame sampling
→ vision detection
→ state tracking
→ LLM only when user asks or a relevant event occurs.

Implement temporal state where possible.

Example:

{
  "person_detected": true,
  "identity": "Manoj",
  "distance": 2.1,
  "position": "center",
  "last_seen": "...",
  "confidence": 0.91
}

---

# 8. OCR FEATURE

The assistant must read text for blind users.

Workflow:

Camera
→ Text Detection
→ OCR
→ Text Cleaning
→ LLM optional summarization
→ TTS

Example:

User:

"Read this."

System detects:

"Emergency Exit"

Assistant:

"Emergency Exit."

For OCR, inspect the existing implementation first.

If a replacement is necessary, evaluate lightweight models such as:

- PaddleOCR
- EasyOCR
- Tesseract for simple fallback

Prefer PaddleOCR or an existing optimized solution for realtime camera use.

OCR should only run when:

1. User explicitly asks to read text, OR
2. Text is strongly detected and the application is in reading mode.

Do not continuously run expensive OCR on every frame.

---

# 9. NAVIGATION FOR BLIND USERS

Navigation should be treated separately from general scene understanding.

The system needs to answer:

- Where am I going?
- Is there an obstacle?
- Is the path clear?
- Is something to my left/right/front?
- How far is the obstacle?
- What direction should I move?

Basic realtime navigation pipeline:

Camera
+
GPS / location if available
+
IMU if available
+
Object detection
+
Depth estimation
+
Path reasoning
↓
Navigation Agent
↓
Voice instruction

Example:

"Obstacle ahead."

"Move slightly to your right."

"Path is clear."

"Door approximately three meters ahead."

For the hackathon MVP, prioritize:

1. Front obstacle detection.
2. Left/right obstacle awareness.
3. Approximate distance.
4. Direction guidance.
5. Voice alerts.

Do NOT attempt a full autonomous GPS navigation system if the existing hardware does not support it.

Make the MVP reliable.

---

# 10. MODE SYSTEM

The assistant should support modes.

Example:

GENERAL MODE

"Ask me anything."

NAVIGATION MODE

Focus on:

- obstacles
- path
- direction
- distance

PERSON MODE

Focus on:

- people
- identity
- saved people

READING MODE

Focus on:

- OCR
- text
- documents

SCENE MODE

Focus on:

- environment
- objects
- surroundings

The router should know the current mode.

Example:

{
  "mode": "navigation",
  "active_agent": "navigation_agent"
}

User can say:

"Start navigation."

→ navigation mode

"Stop navigation."

→ general mode

"Read mode."

→ OCR mode

"Who is there?"

→ person/scene agent

---

# 11. LLM RESPONSIBILITY

The LLM should NOT be the vision system.

The LLM should act as:

- reasoning layer
- intent classifier
- agent router
- response generator
- conversational interface

Vision models should handle:

- face detection
- face recognition
- object detection
- OCR
- depth estimation

The LLM receives structured data.

Example:

{
  "mode": "general",
  "user_query": "What is in front of me?",
  "vision": {
      "people": [
          {
              "name": "Manoj",
              "distance": 2.1,
              "position": "center"
          }
      ],
      "objects": [
          {
              "type": "chair",
              "distance": 1.5,
              "position": "right"
          }
      ]
  }
}

LLM response:

"Manoj is about two meters in front of you. There is a chair slightly to your right."

Keep responses:

- short
- clear
- useful
- voice-friendly

---

# 12. REALTIME PERFORMANCE

Do not process everything at maximum frequency.

Use different processing rates.

Example:

Camera:
20–30 FPS

Object detection:
5–10 FPS

Face recognition:
1–3 FPS

OCR:
On demand

LLM:
On demand

TTS:
Only when response is required

This is critical for realtime performance.

---

# 13. THREE-DEVELOPER PARALLEL WORK

IMPORTANT:

Three developers must work at the same time.

We want to minimize merge conflicts.

DO NOT have all three developers edit:

- main app entry point
- router file
- shared configuration
- package files
- same frontend component
- same backend controller

at the same time.

Divide the repository by ownership.

---

# DEVELOPER 1 — AI / LLM / VOICE

Owner:

/ai
/agents
/voice
/conversation
/router

Responsibilities:

1. Understand existing LLM integration.
2. Implement Agent Router.
3. Implement mode handling.
4. Implement intent classification.
5. Implement conversation agent.
6. Implement realtime voice interaction.
7. Implement structured context passed to LLM.
8. Implement concise TTS responses.
9. Ensure LLM is not unnecessarily called for vision tasks.

Deliver clean interfaces such as:

route_intent()

process_command()

generate_response()

set_mode()

get_current_mode()

Do NOT modify vision implementation.

Do NOT modify navigation implementation.

---

# DEVELOPER 2 — COMPUTER VISION

Owner:

/vision
/person
/ocr
/face
/object_detection

Responsibilities:

1. Camera frame processing.
2. Person detection.
3. Face detection.
4. Face embedding.
5. Person recognition.
6. Save person feature.
7. Recognize saved person.
8. OCR.
9. Object detection.
10. Structured scene output.

Example interface:

process_frame(frame)

detect_people(frame)

recognize_people(frame)

save_person(name, face)

read_text(frame)

detect_objects(frame)

get_scene_state()

Return structured JSON/data.

Example:

{
    "people": [],
    "objects": [],
    "text": [],
    "timestamp": 123
}

Do NOT modify the LLM router.

Do NOT modify navigation logic.

---

# DEVELOPER 3 — NAVIGATION / BACKEND / INTEGRATION

Owner:

/navigation
/backend
/api
/integration
/tests

Responsibilities:

1. Navigation mode.
2. Obstacle detection integration.
3. Distance estimation.
4. Left/right/front positioning.
5. Navigation instructions.
6. API endpoints.
7. Connect vision output to navigation.
8. Integration testing.
9. Demo workflow.
10. Current project report.
11. Performance testing.

Navigation interface:

start_navigation()

stop_navigation()

get_navigation_state()

get_instruction()

Example output:

{
    "obstacle": true,
    "direction": "right",
    "distance": 1.8,
    "instruction": "Move slightly right"
}

Do NOT modify the face recognition implementation.

Do NOT rewrite the LLM implementation.

---

# 14. SHARED CONTRACT

All developers MUST communicate through stable interfaces.

Vision produces:

SceneState

Navigation consumes SceneState.

Agent Router consumes:

UserIntent + SceneState + Mode

LLM produces:

AssistantResponse

TTS consumes:

AssistantResponse

Example architecture:

Camera
 ↓
VisionService
 ↓
SceneState
 ↓
AgentRouter
 ↓
SpecializedAgent
 ↓
AssistantResponse
 ↓
TTS

Do not directly import internal implementation details from another developer's module.

---

# 15. GIT RULES

Each developer must work on a separate branch.

Developer 1:

feature/ai-agent-router

Developer 2:

feature/computer-vision

Developer 3:

feature/navigation-integration

Before editing:

git pull

Each developer should commit only their own module.

Use small commits:

feat: add agent router

feat: add face recognition

feat: add OCR service

feat: add navigation state

feat: add realtime voice handler

Avoid huge commits.

Do NOT modify unrelated files.

---

# 16. MERGE-CONFLICT RULE

Before modifying a shared file:

STOP.

Check whether another developer owns it.

If yes, create an interface instead of editing their implementation.

Example:

BAD:

Developer 1 directly edits Developer 2's face recognition code.

GOOD:

Developer 1 calls:

person_service.recognize_person()

The implementation remains owned by Developer 2.

---

# 17. HACKATHON MVP PRIORITY

Do NOT try to perfect everything.

Priority order:

P0 — MUST WORK

1. Camera input.
2. Voice input.
3. Voice output.
4. Agent routing.
5. Person detection.
6. Save person by voice.
7. Recognize saved person.
8. Basic OCR.
9. Basic obstacle detection.
10. Navigation voice instruction.

P1 — SHOULD WORK

11. Object detection.
12. Scene description.
13. Realtime person state.
14. Mode switching.
15. Better response generation.

P2 — IF TIME REMAINS

16. Advanced navigation.
17. Better distance estimation.
18. LLM vision reasoning.
19. Personalization.
20. Advanced conversation.

---

# 18. DEMO FLOW

The final demo should follow this sequence.

DEMO 1 — GENERAL ASSISTANT

User:

"Hey assistant."

Assistant:

"Yes, I'm listening."

User:

"What is around me?"

Assistant uses camera + vision.

Response:

"There is a person in front of you and a chair on your right."

---

DEMO 2 — SAVE PERSON

User:

"Save this person as Manoj."

System detects face.

Assistant:

"I've saved this person as Manoj."

---

DEMO 3 — RECOGNIZE PERSON

Camera sees same person.

Assistant:

"Manoj is in front of you."

---

DEMO 4 — OCR

User:

"Read this."

Camera sees text.

Assistant:

"The text says Emergency Exit."

---

DEMO 5 — NAVIGATION

User:

"Start navigation."

Assistant:

"Navigation mode started."

Obstacle appears.

Assistant:

"Obstacle ahead. Move slightly right."

---

# 19. SAFETY

This is an accessibility assistant.

Never present uncertain recognition as certain.

Bad:

"This is Manoj."

when confidence is low.

Better:

"I think this may be Manoj."

For navigation:

Bad:

"Walk forward. The path is definitely clear."

Better:

"The path appears clear for about two meters."

The system must communicate uncertainty.

---

# 20. PERFORMANCE REQUIREMENTS

Target:

Voice response:
< 2–3 seconds where possible.

Vision:
Realtime enough for demo.

Person recognition:
Fast enough to feel realtime.

OCR:
On-demand.

LLM:
Only when useful.

Avoid blocking the camera pipeline.

Use asynchronous processing where appropriate.

---

# 21. CURRENT PROJECT REPORT

Before finishing, create/update:

PROJECT_STATUS.md

Include:

## Current Architecture

## Implemented Features

## Missing Features

## Models Used

## APIs Used

## Agent Architecture

## Person Recognition Flow

## OCR Flow

## Navigation Flow

## Voice Flow

## Known Issues

## Hackathon Demo Flow

## Future Improvements

Also include:

Feature | Before | Now | Status

---

# 22. FINAL INTEGRATION CHECK

After all three developers finish:

1. Merge branches.
2. Resolve conflicts carefully.
3. Run the complete application.
4. Test camera.
5. Test microphone.
6. Test TTS.
7. Test LLM.
8. Test agent routing.
9. Test person save.
10. Test person recognition.
11. Test OCR.
12. Test navigation.
13. Test mode switching.
14. Test failure cases.
15. Test latency.
16. Test demo from beginning to end.

Do NOT declare completion based only on unit tests.

Perform a real end-to-end demo.

---

# 23. MOST IMPORTANT RULE

We have limited hackathon time.

DO NOT spend hours rebuilding architecture.

First:

UNDERSTAND → REUSE → CONNECT → TEST → IMPROVE.

If an existing feature already works:

KEEP IT.

If a feature partially works:

FIX IT.

If a feature is missing:

IMPLEMENT THE SMALLEST RELIABLE MVP.

Do not introduce unnecessary technologies.

Do not replace working models without a measurable reason.

Do not create duplicate implementations.

The goal is a working demo, not a theoretically perfect architecture.
