import os
import pytest
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)


def test_health_check():
    """Verify health endpoint responds with 200 OK."""
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert "livekit_configured" in data


def test_token_missing_params():
    """Verify 422 error when required query params are missing."""
    response = client.get("/token")
    assert response.status_code == 422


def test_token_server_missing_env(monkeypatch):
    """Verify proper 500 error when LIVEKIT_API_KEY is not configured."""
    monkeypatch.delenv("LIVEKIT_API_KEY", raising=False)
    monkeypatch.delenv("LIVEKIT_API_SECRET", raising=False)
    response = client.get("/token?room=drishti-room&user=user-1")
    assert response.status_code == 500
    assert "LIVEKIT_API_KEY" in response.json()["detail"]


def test_token_generation_success(monkeypatch):
    """Verify successful JWT token creation when credentials are set."""
    monkeypatch.setenv("LIVEKIT_API_KEY", "test_api_key_123")
    monkeypatch.setenv("LIVEKIT_API_SECRET", "test_secret_key_12345678901234567890")
    response = client.get("/token?room=drishti-demo&user=vis_user_42")
    assert response.status_code == 200
    data = response.json()
    assert "token" in data
    assert data["room"] == "drishti-demo"
    assert data["identity"] == "vis_user_42"
    # Basic JWT format check: three dot-separated base64 parts
    token_parts = data["token"].split(".")
    assert len(token_parts) == 3
