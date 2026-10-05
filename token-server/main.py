import os
import logging
from typing import Optional
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from livekit.api import AccessToken, VideoGrants, LiveKitAPI, CreateAgentDispatchRequest
import uvicorn
from dotenv import load_dotenv

# Load environment configuration
load_dotenv()

# Setup logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("drishti-token-server")

app = FastAPI(
    title="Drishti LiveKit Token Server",
    description="Authentication and token generation service for Drishti Mobile Voice Companion",
    version="1.0.0"
)

# Enable CORS for mobile and web clients
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health_check():
    """Health check endpoint to verify server is active and configured."""
    api_key_configured = bool(os.getenv("LIVEKIT_API_KEY"))
    api_secret_configured = bool(os.getenv("LIVEKIT_API_SECRET"))
    return {
        "status": "healthy",
        "livekit_configured": api_key_configured and api_secret_configured
    }


@app.get("/token")
async def get_token(
    room: str = Query(..., description="Target LiveKit room name"),
    user: str = Query(..., description="Unique participant identity or user name")
):
    """
    Issues a signed JWT token granting audio, video, and data publishing permissions
    for the specified user in the requested room, and ensures an agent worker is dispatched.
    """
    api_key = os.getenv("LIVEKIT_API_KEY")
    api_secret = os.getenv("LIVEKIT_API_SECRET")
    livekit_url = os.getenv("LIVEKIT_URL")

    if not api_key or not api_secret:
        logger.error("Token request rejected: LIVEKIT_API_KEY or LIVEKIT_API_SECRET missing in environment")
        raise HTTPException(
            status_code=500,
            detail="Server misconfiguration: LIVEKIT_API_KEY or LIVEKIT_API_SECRET not set."
        )

    try:
        grants = VideoGrants(
            room_join=True,
            room=room,
            can_publish=True,
            can_subscribe=True,
            can_publish_data=True
        )

        token = AccessToken(api_key, api_secret)
        token.with_identity(user)
        token.with_grants(grants)
        jwt_token = token.to_jwt()

        logger.info(f"Generated LiveKit token for user '{user}' in room '{room}'")

        # Auto-dispatch agent to the room so the worker receives the job immediately
        if livekit_url:
            try:
                lk_api = LiveKitAPI(livekit_url, api_key, api_secret)
                has_active_agent = False
                try:
                    dispatches = await lk_api.agent_dispatch.list_dispatch(room)
                    for d in dispatches:
                        if d.agent_name in ["drishti-agent", ""]:
                            # If no jobs recorded yet, dispatch is still pending/creating
                            if not d.state.jobs:
                                has_active_agent = True
                                break
                            # Check if any job is currently pending (0) or running (1)
                            for job in d.state.jobs:
                                if job.state.status in [0, 1]:
                                    has_active_agent = True
                                    break
                except Exception:
                    has_active_agent = False

                if not has_active_agent:
                    await lk_api.agent_dispatch.create_dispatch(
                        CreateAgentDispatchRequest(room=room, agent_name="drishti-agent")
                    )
                    logger.info(f"Dispatched agent 'drishti-agent' to room '{room}'")
                await lk_api.aclose()
            except Exception as dispatch_err:
                logger.warning(f"Could not auto-dispatch agent to room '{room}': {dispatch_err}")

        return {
            "token": jwt_token,
            "room": room,
            "identity": user
        }
    except Exception as exc:
        logger.exception("Failed to generate token")
        raise HTTPException(status_code=500, detail=f"Token creation failed: {str(exc)}")


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8000"))
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=True)
