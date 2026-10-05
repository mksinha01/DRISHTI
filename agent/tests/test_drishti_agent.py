import pytest
import json
from drishti_agent import (
    PromptManager,
    AgentIntentRouter,
    NavigationService,
    VisionService,
    DrishtiAgent,
)


def test_prompt_manager_modes():
    """Verify distinct and well-formed prompts exist for all modes."""
    modes = ["assistant", "navigation", "ocr", "emergency"]
    for mode in modes:
        prompt = PromptManager.get_prompt(mode)
        assert prompt is not None
        assert len(prompt) > 30
        assert "Drishti" in prompt


def test_prompt_manager_fallback():
    """Verify fallback to assistant prompt for unknown mode."""
    prompt = PromptManager.get_prompt("nonexistent_mode")
    assert prompt == PromptManager.get_prompt("assistant")


def test_intent_router_emergency():
    """Verify voice emergency triggers and cancellation."""
    intent, payload = AgentIntentRouter.classify_intent("help emergency, please!")
    assert intent == "EMERGENCY_TRIGGER"
    assert payload["reason"] == "voice_phrase"

    cancel_intent, _ = AgentIntentRouter.classify_intent("cancel")
    assert cancel_intent == "EMERGENCY_CANCEL"

    cancel_intent2, _ = AgentIntentRouter.classify_intent("stop emergency")
    assert cancel_intent2 == "EMERGENCY_CANCEL"


def test_intent_router_mode_switching():
    """Verify switching between assistant, navigation, and OCR."""
    intent, payload = AgentIntentRouter.classify_intent("switch to navigation")
    assert intent == "SWITCH_MODE"
    assert payload["target_mode"] == "navigation"

    intent, payload = AgentIntentRouter.classify_intent("change to read mode")
    assert intent == "SWITCH_MODE"
    assert payload["target_mode"] == "ocr"

    intent, payload = AgentIntentRouter.classify_intent("switch to assistant")
    assert intent == "SWITCH_MODE"
    assert payload["target_mode"] == "assistant"


def test_intent_router_navigation():
    """Verify destination extraction from natural speech."""
    intent, payload = AgentIntentRouter.classify_intent("take me to Central Park")
    assert intent == "NAVIGATION_ROUTE"
    assert payload["destination"] == "Central Park"

    intent, payload = AgentIntentRouter.classify_intent("directions to metro station")
    assert intent == "NAVIGATION_ROUTE"
    assert payload["destination"] == "Metro Station"


def test_intent_router_ocr():
    """Verify OCR read and summarize requests."""
    intent, payload = AgentIntentRouter.classify_intent("read this sign")
    assert intent == "OCR_REQUEST"
    assert payload["action"] == "read"

    intent, payload = AgentIntentRouter.classify_intent("summarize this label")
    assert intent == "OCR_REQUEST"
    assert payload["action"] == "summarize"


def test_navigation_service():
    """Verify route planning data structure and step bearings."""
    route = NavigationService.plan_route("City Hospital")
    assert route["destination"] == "City Hospital"
    assert route["total_distance_m"] > 0
    assert len(route["steps"]) >= 2
    assert "bearing" in route["steps"][0]


def test_vision_service_formatting():
    """Verify natural formatting of detected objects and text."""
    desc = VisionService.format_scene_description(["chair", "table"], "Entry Gate")
    assert "chair, table" in desc
    assert "Entry Gate" in desc

    empty_desc = VisionService.format_scene_description([])
    assert "clear" in empty_desc.lower()


def test_drishti_agent_data_packets():
    """Verify data channel handling for OCR, obstacles, and mode changes."""
    agent = DrishtiAgent(initial_mode="assistant")
    assert agent.mode == "assistant"

    # Test mode change packet
    mode_pkt = json.dumps({"type": "MODE_CHANGE", "mode": "navigation"})
    res = agent.handle_data_packet(mode_pkt)
    assert res["status"] == "ok"
    assert agent.mode == "navigation"

    # Test OCR packet
    ocr_pkt = json.dumps({"type": "OCR_RESULT", "text": "Platform 3 Express"})
    res = agent.handle_data_packet(ocr_pkt)
    assert res["status"] == "ok"
    assert agent.latest_ocr_text == "Platform 3 Express"

    # Test Obstacle packet
    obs_pkt = json.dumps({"type": "OBSTACLE_WARNING", "obstacle": "stairs", "position": "center"})
    res = agent.handle_data_packet(obs_pkt)
    assert res["status"] == "ok"
    assert res["obstacle"] == "stairs"
