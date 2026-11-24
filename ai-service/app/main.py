"""HTTP entry point. Stateless: Spring sends everything a turn needs."""

import hmac
import logging
import os
from datetime import UTC, datetime
from functools import cache
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Response

from app.agent import run_turn
from app.contracts import TurnRequest, TurnResponse
from app.llm import AnthropicProvider, LLMProvider
from app.tools import ToolContext

# One JSON line per turn on the "agent" logger: the trace (propuesta section 10).
# ponytail: stdout logs instead of Langfuse/OTel; ship them to one if the
# host's log viewer stops being enough.
logging.basicConfig(level=logging.INFO, format="%(message)s")

app = FastAPI(title="turnos ai-service")


@cache
def get_llm() -> LLMProvider:
    return AnthropicProvider(
        model=os.environ.get("AI_MODEL", "claude-haiku-5-5"), effort=os.environ.get("AI_EFFORT", "low")
    )


@cache
def get_core() -> httpx.AsyncClient:
    return httpx.AsyncClient(base_url=os.environ.get("CORE_BASE_URL", "http://localhost:8080"), timeout=10)


def require_service_token(authorization: Annotated[str | None, Header()] = None) -> None:
    # ai-service is publicly reachable on Render, so this shared secret is
    # what keeps strangers from spending the LLM budget.
    expected = os.environ.get("AI_SERVICE_TOKEN", "")
    if not expected or not hmac.compare_digest(authorization or "", f"Bearer {expected}"):
        raise HTTPException(status_code=401)


@app.get("/health")
def health(response: Response) -> dict[str, str]:
    # The chat checks this from the browser (frontend wakeAi.ts) to know the
    # service is really up, so the browser must be allowed to read it. Only
    # this route: nothing in it is secret; /v1/agent/turn stays server-to-server.
    response.headers["Access-Control-Allow-Origin"] = "*"
    return {"status": "ok"}


@app.post("/v1/agent/turn", dependencies=[Depends(require_service_token)])
async def turn(
    req: TurnRequest,
    llm: Annotated[LLMProvider, Depends(get_llm)],
    core: Annotated[httpx.AsyncClient, Depends(get_core)],
) -> TurnResponse:
    return await run_turn(req, llm, ToolContext(core, req.core_token), now=datetime.now(UTC))
