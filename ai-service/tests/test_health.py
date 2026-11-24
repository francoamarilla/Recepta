"""/health is readable from the browser (the chat's wake check); nothing else is."""

from fastapi.testclient import TestClient

from app.main import app

ORIGIN = {"Origin": "https://recepta-wheat.vercel.app"}
client = TestClient(app)


def test_health_is_readable_cross_origin() -> None:
    r = client.get("/health", headers=ORIGIN)
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}
    assert r.headers["access-control-allow-origin"] == "*"


def test_agent_turn_gets_no_cors_and_still_needs_the_token(monkeypatch) -> None:
    monkeypatch.setenv("AI_SERVICE_TOKEN", "test-token")

    no_token = client.post("/v1/agent/turn", json={}, headers=ORIGIN)
    wrong_token = client.post("/v1/agent/turn", json={}, headers={**ORIGIN, "Authorization": "Bearer nope"})
    for r in (no_token, wrong_token):
        assert r.status_code == 401
        assert "access-control-allow-origin" not in r.headers

    # a browser preflight for the turn endpoint is not answered with CORS either
    preflight = client.options(
        "/v1/agent/turn",
        headers={**ORIGIN, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "authorization"},
    )
    assert "access-control-allow-origin" not in preflight.headers
