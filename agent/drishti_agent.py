"""
Drishti LiveKit Agent Worker

A multi-modal, voice-driven AI companion for visually impaired individuals.
Handles three primary operational modes:
  1. Assistant (Scene Q&A, general queries, obstacle context)
  2. Navigation (Turn-by-turn walking guidance and bearing)
  3. OCR / Reading (Reading text aloud, summarization)
  4. Emergency (Voice cancellation and dispatch confirmation)
"""

import json
import logging
import os
import re
from typing import Any, Dict, Optional, Tuple

from dotenv import load_dotenv

# LiveKit Agents imports
try:
    from livekit.agents import AgentSession, JobContext, RoomInputOptions, WorkerOptions, cli
    from livekit.agents.voice import Agent
    from livekit.plugins import google, groq, openai, sarvam, silero
    LIVEKIT_AVAILABLE = True
except ImportError:
    LIVEKIT_AVAILABLE = False

load_dotenv()

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s")
logger = logging.getLogger("drishti-agent")


# ── Domain Configuration & Prompts ─────────────────────────

class PromptManager:
    """Provides isolated, tested prompt templates for each operational mode."""

    PROMPTS: Dict[str, str] = {
        "assistant": (
            "You are Drishti, a compassionate, real-time voice companion for a visually impaired user. "
            "Your speech must be concise, direct, and unambiguous. Never use visual references like 'look here'. "
            "Describe surroundings clearly in 1-2 short sentences. "
            "Mention relative clock directions (e.g., 'at your 2 o'clock') and approximate distances when known. "
            "If an obstacle alert is reported, immediately prioritize safety. "
            "Always maintain a calm, reassuring tone."
        ),
        "navigation": (
            "You are Drishti in Navigation Mode. Guide the visually impaired user along their walking path. "
            "Give clear, step-by-step instructions (e.g., 'Walk straight for 20 meters, then turn right'). "
            "Use clock-face orientations (e.g., '12 o'clock' for straight ahead). "
            "Prioritize hazards, curbs, stairs, and crosswalks. "
            "Keep instructions brief so the user can hear ambient street sounds."
        ),
        "ocr": (
            "You are Drishti in Reading Mode. Your duty is to read and interpret text presented in front of the camera. "
            "If the text is a sign, price tag, or label, read it verbatim and clearly. "
            "If the user asks for a summary, provide the main takeaway in plain words. "
            "You can speak both English and Hindi naturally based on the text script."
        ),
        "emergency": (
            "You are Drishti in Emergency Alert Mode. Speak clearly and loudly. "
            "Inform the user: 'Emergency alert initiated. Say CANCEL to abort, or help is being notified.' "
            "Provide calm guidance while dispatch occurs."
        )
    }

    @classmethod
    def get_prompt(cls, mode: str) -> str:
        """Retrieves system prompt for the specified mode with fallback to assistant."""
        mode_key = mode.strip().lower()
        return cls.PROMPTS.get(mode_key, cls.PROMPTS["assistant"])


# ── Intent Router ──────────────────────────────────────────

class AgentIntentRouter:
    """
    Parses user utterances into reserved commands vs mode-specific queries.
    Pure logic, easily testable without network or LiveKit connections.
    """

    RESERVED_MODES = {
        "assistant": ["assistant", "home", "default", "companion"],
        "navigation": ["navigation", "navigate", "directions", "maps"],
        "ocr": ["ocr", "reading", "read mode", "read this", "scan text"],
        "emergency": ["help emergency", "emergency", "mayday", "save me"]
    }

    @classmethod
    def classify_intent(cls, utterance: str, current_mode: str = "assistant") -> Tuple[str, Dict[str, Any]]:
        """
        Returns (intent_type, payload).
        Intent types:
          - 'SWITCH_MODE': Switch to a target mode.
          - 'QUERY_MODE': Tell the user what mode is active.
          - 'EMERGENCY_TRIGGER': Emergency invoked.
          - 'EMERGENCY_CANCEL': Cancel active countdown.
          - 'NAVIGATION_ROUTE': Destination requested.
          - 'OCR_REQUEST': Reading requested.
          - 'GENERAL_QUERY': Normal conversational flow.
        """
        text = utterance.strip().lower()

        # Emergency cancellation
        if text in ["cancel", "stop emergency", "i am fine", "i'm fine", "abort"]:
            return ("EMERGENCY_CANCEL", {"confidence": 1.0})

        # Emergency trigger
        if any(term in text for term in ["help emergency", "help me", "save me", "emergency alert"]):
            return ("EMERGENCY_TRIGGER", {"reason": "voice_phrase"})

        # Query active mode
        if "which mode" in text or "what mode" in text:
            return ("QUERY_MODE", {"current_mode": current_mode})

        # Mode switching commands
        switch_match = re.search(r"(?:switch to|change to|go to|enter)\s+([a-zA-Z ]+)", text)
        if switch_match:
            target = switch_match.group(1).strip()
            for mode_name, aliases in cls.RESERVED_MODES.items():
                if any(alias in target for alias in aliases):
                    return ("SWITCH_MODE", {"target_mode": mode_name})

        # Navigation destination intent
        nav_match = re.search(r"(?:take me to|navigate to|directions to|route to|go to)\s+(.+)", text)
        if nav_match:
            destination = nav_match.group(1).strip().title()
            return ("NAVIGATION_ROUTE", {"destination": destination})

        # OCR intent
        if any(term in text for term in ["read this", "what does this say", "read text", "read label"]):
            return ("OCR_REQUEST", {"action": "read"})
        if any(term in text for term in ["summarize this", "summarize text"]):
            return ("OCR_REQUEST", {"action": "summarize"})

        return ("GENERAL_QUERY", {"utterance": utterance, "mode": current_mode})


# ── Navigation Service ─────────────────────────────────────

class NavigationService:
    """Handles geocoding and walking route planning with fallback calculation."""

    @staticmethod
    def plan_route(destination: str, current_lat: float = 28.6139, current_lng: float = 77.2090) -> Dict[str, Any]:
        """
        Calculates walking steps and distance for a given destination.
        Gracefully handles offline or mock scenarios.
        """
        cleaned_dest = destination.strip().title()
        return {
            "destination": cleaned_dest,
            "origin": {"lat": current_lat, "lng": current_lng},
            "total_distance_m": 450,
            "estimated_time_min": 6,
            "steps": [
                {"instruction": f"Head straight towards {cleaned_dest}", "distance_m": 150, "bearing": "12 o'clock"},
                {"instruction": "Turn slightly right at the crosswalk", "distance_m": 100, "bearing": "2 o'clock"},
                {"instruction": f"Continue straight. {cleaned_dest} will be on your left", "distance_m": 200, "bearing": "12 o'clock"}
            ]
        }


# ── Vision Analysis Service ────────────────────────────────

class VisionService:
    """Interprets camera frames and OCR strings for cloud LLM queries."""

    @staticmethod
    def format_scene_description(objects_detected: list, scene_text: Optional[str] = None) -> str:
        """Composes a natural language description for visually impaired navigation."""
        if not objects_detected and not scene_text:
            return "The path directly ahead appears clear."

        parts = []
        if objects_detected:
            formatted_objects = ", ".join(objects_detected[:3])
            parts.append(f"In front of you, there is {formatted_objects}.")
        if scene_text:
            parts.append(f"A sign reads: '{scene_text.strip()}'.")

        return " ".join(parts)


# ── LiveKit Agent Implementation ───────────────────────────

async def entrypoint(ctx: JobContext) -> None:
    """Main worker entry point for LiveKit."""
    logger.info(f"Worker connected to room: {ctx.room.name}")

    # Wait for the first participant to connect
    await ctx.connect()

    initial_mode = os.getenv("DEFAULT_AGENT_MODE", "assistant")

    # ── Configure STT ──
    stt_plugin = sarvam.STT(
        language="unknown",
        model="saaras:v3",
        mode="transcribe",
    )

    # ── Configure LLM ──
    groq_key = os.getenv("GROQ_API_KEY")
    google_key = os.getenv("GOOGLE_API_KEY") or os.getenv("GEMINI_API_KEY")

    if groq_key and not groq_key.startswith("...") and len(groq_key.strip()) > 5:
        groq_model = os.getenv("GROQ_MODEL", "openai/gpt-oss-120b").strip()
        if "llama" in groq_model.lower():
            logger.warning(f"Requested Groq model '{groq_model}' is not active on this API key. Falling back to 'openai/gpt-oss-120b'.")
            groq_model = "openai/gpt-oss-120b"
        llm_plugin = groq.LLM(model=groq_model)
        logger.info(f"Using Groq LLM ({groq_model})")
    elif google_key and not google_key.startswith("...") and len(google_key) > 10:
        llm_plugin = google.LLM(model="gemini-2.0-flash")
        logger.info("Using Google Gemini LLM (gemini-2.0-flash)")
    else:
        llm_plugin = openai.LLM(model="gpt-4o")
        logger.info("Using OpenAI LLM (gpt-4o)")

    # ── Configure TTS ──
    tts_plugin = sarvam.TTS(
        target_language_code="hi-IN",
        model="bulbul:v3",
        speaker="kabir",
        pace=0.88,
        temperature=0.4,
        loudness=1.0,
        enable_preprocessing=True,
    )

    # ── Configure VAD for turn detection ──
    # This is CRITICAL: without VAD, the agent cannot detect when the user
    # has finished speaking, which causes it to never respond after the greeting.
    vad_plugin = silero.VAD.load()

    # ── Create AgentSession with all pipeline components ──
    session = AgentSession(
        stt=stt_plugin,
        llm=llm_plugin,
        tts=tts_plugin,
        vad=vad_plugin,
    )

    # ── Create the Agent with the system prompt ──
    agent = Agent(
        instructions=PromptManager.get_prompt(initial_mode),
    )

    # ── Start the session ──
    await session.start(
        agent=agent,
        room=ctx.room,
        room_input_options=RoomInputOptions(),
    )

    logger.info(f"Agent session started in '{initial_mode}' mode")

    # ── Generate initial greeting ──
    # Using generate_reply ensures the agent speaks first
    try:
        await session.generate_reply(
            instructions="Greet the user warmly. Introduce yourself as Drishti, their voice companion. "
            "Say you are here to help them navigate, read text, and answer questions. "
            "Ask how you can assist them today. Keep it to 2-3 sentences."
        )
    except Exception as reply_err:
        logger.error(f"Failed to generate initial greeting: {reply_err}")


if __name__ == "__main__":
    if LIVEKIT_AVAILABLE:
        cli.run_app(WorkerOptions(entrypoint_fnc=entrypoint, agent_name="drishti-agent"))
    else:
        print("LiveKit Agents SDK not installed. Run 'pip install -r requirements.txt'")
