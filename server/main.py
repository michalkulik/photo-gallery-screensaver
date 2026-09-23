"""OAuth relay for the Photo Gallery Screensaver Android TV app.

Why this exists
---------------
Google's device flow (the "type a code on your phone" flow) only supports a
small allow-list of scopes and does NOT include the Photos Picker scope, so a
TV with no browser cannot obtain a Google Photos token on its own. Google also
refuses OAuth inside a WebView, and the TV has no browser app at all.

This service bridges that gap: the TV asks for a session, the user authorises on
their phone through a normal web redirect, and the TV polls this service to
collect the resulting tokens. The TV never needs a browser.

The service deliberately does NOT talk to the Photos Picker API. It only
performs the OAuth dance; the TV keeps managing picker sessions itself.

Token delivery
--------------
Two separate identifiers are used so that seeing the QR code is not enough to
steal the tokens:

* ``id``          - secret, returned only to the TV, used to poll for tokens.
* ``flow_token``  - public, embedded in the QR code, used as the OAuth ``state``.

Anyone who photographs the QR learns only ``flow_token``, which cannot be used
to read the tokens back out.
"""

from __future__ import annotations

import base64
import hashlib
import html
import os
import secrets
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Optional

import httpx
from fastapi import FastAPI, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel

# --- Configuration ---------------------------------------------------------------------

GOOGLE_CLIENT_ID = os.environ.get("GOOGLE_CLIENT_ID", "").strip()
GOOGLE_CLIENT_SECRET = os.environ.get("GOOGLE_CLIENT_SECRET", "").strip()
PUBLIC_BASE_URL = os.environ.get("PUBLIC_BASE_URL", "").strip().rstrip("/")
SCOPE = os.environ.get(
    "PHOTOS_SCOPE",
    "https://www.googleapis.com/auth/photospicker.mediaitems.readonly",
).strip()

# How long a session may wait for the user to finish authorising.
SESSION_TTL_SECONDS = int(os.environ.get("SESSION_TTL_SECONDS", "900"))

# How long a completed session is kept so the TV can collect its tokens.
DELIVERY_TTL_SECONDS = int(os.environ.get("DELIVERY_TTL_SECONDS", "300"))

GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

REDIRECT_URI = f"{PUBLIC_BASE_URL}/oauth/callback"

_http: Optional[httpx.AsyncClient] = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global _http
    _http = httpx.AsyncClient(timeout=httpx.Timeout(20.0))
    try:
        yield
    finally:
        await _http.aclose()
        _http = None


app = FastAPI(
    title="Photo Gallery Screensaver relay",
    docs_url=None,
    redoc_url=None,
    lifespan=lifespan,
)


# --- Session store ---------------------------------------------------------------------


@dataclass
class Session:
    id: str
    flow_token: str
    code_verifier: str
    created_at: float
    status: str = "pending"  # pending | ready | error
    access_token: Optional[str] = None
    refresh_token: Optional[str] = None
    expires_in: Optional[int] = None
    scope: Optional[str] = None
    error: Optional[str] = None
    completed_at: Optional[float] = None


_sessions_by_id: dict[str, Session] = {}
_sessions_by_flow: dict[str, Session] = {}


def _purge_expired() -> None:
    """Drops sessions that are past their useful life. Called on every request."""
    now = time.time()
    for session in list(_sessions_by_id.values()):
        limit = SESSION_TTL_SECONDS
        if session.completed_at is not None:
            limit = DELIVERY_TTL_SECONDS
            age = now - session.completed_at
        else:
            age = now - session.created_at
        if age > limit:
            _sessions_by_id.pop(session.id, None)
            _sessions_by_flow.pop(session.flow_token, None)


def _pkce_pair() -> tuple[str, str]:
    """Returns ``(verifier, challenge)`` for PKCE S256."""
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(48)).rstrip(b"=").decode()
    digest = hashlib.sha256(verifier.encode()).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode()
    return verifier, challenge


def _configured() -> bool:
    return bool(GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET and PUBLIC_BASE_URL)


# --- API used by the TV -----------------------------------------------------------------


@app.get("/health")
async def health() -> JSONResponse:
    return JSONResponse({"status": "ok", "configured": _configured()})


@app.post("/api/session")
async def create_session() -> JSONResponse:
    _purge_expired()
    if not _configured():
        return JSONResponse(
            {"error": "relay_not_configured"},
            status_code=503,
        )

    verifier, _ = _pkce_pair()
    session = Session(
        id=secrets.token_urlsafe(32),
        flow_token=secrets.token_urlsafe(24),
        code_verifier=verifier,
        created_at=time.time(),
    )
    _sessions_by_id[session.id] = session
    _sessions_by_flow[session.flow_token] = session

    return JSONResponse(
        {
            "id": session.id,
            "auth_url": f"{PUBLIC_BASE_URL}/s/{session.flow_token}",
            "expires_in": SESSION_TTL_SECONDS,
            "poll_interval_seconds": 3,
        }
    )


@app.get("/api/session/{session_id}")
async def get_session(session_id: str) -> JSONResponse:
    _purge_expired()
    session = _sessions_by_id.get(session_id)
    if session is None:
        return JSONResponse({"status": "expired"}, status_code=404)

    if session.status == "pending":
        return JSONResponse({"status": "pending"})

    if session.status == "error":
        return JSONResponse({"status": "error", "error": session.error})

    return JSONResponse(
        {
            "status": "ready",
            "access_token": session.access_token,
            "refresh_token": session.refresh_token,
            "expires_in": session.expires_in,
            "scope": session.scope,
        }
    )


@app.delete("/api/session/{session_id}")
async def delete_session(session_id: str) -> JSONResponse:
    """Lets the TV drop the session as soon as it has stored the tokens."""
    session = _sessions_by_id.pop(session_id, None)
    if session is not None:
        _sessions_by_flow.pop(session.flow_token, None)
    return JSONResponse({"status": "deleted"})


class RefreshRequest(BaseModel):
    refresh_token: str


@app.post("/api/refresh")
async def refresh_access_token(request: RefreshRequest) -> JSONResponse:
    """Exchanges a refresh token for a new access token.

    This lives here so the TV never has to store the client secret: the app only
    ever holds the refresh token, which the user can revoke at any time.
    """
    if not _configured():
        return JSONResponse({"error": "relay_not_configured"}, status_code=503)
    if not request.refresh_token.strip():
        return JSONResponse({"error": "missing_refresh_token"}, status_code=400)

    try:
        token = await _refresh_token(request.refresh_token)
    except Exception as exc:
        # The message carries Google's own error code so the app can detect
        # invalid_grant and force a fresh sign-in.
        return JSONResponse({"error": str(exc)[:300]}, status_code=400)

    return JSONResponse(
        {
            "access_token": token.get("access_token"),
            "expires_in": token.get("expires_in"),
            "scope": token.get("scope"),
        }
    )


# --- Browser pages ----------------------------------------------------------------------


@app.get("/s/{flow_token}")
async def start_authorization(flow_token: str) -> Response:
    """Entry point scanned from the TV. Bounces the phone to Google."""
    _purge_expired()
    session = _sessions_by_flow.get(flow_token)
    if session is None:
        return _page(
            "Link wygasł",
            "Ten link wygasł albo został już użyty. Uruchom logowanie ponownie na telewizorze.",
            status_code=410,
        )

    _, challenge = _pkce_pair_from_verifier(session.code_verifier)
    params = {
        "client_id": GOOGLE_CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": SCOPE,
        "state": session.flow_token,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        # Both are needed to reliably receive a refresh token.
        "access_type": "offline",
        "prompt": "consent",
    }
    return RedirectResponse(_url_with_params(GOOGLE_AUTH_ENDPOINT, params), status_code=302)


@app.get("/oauth/callback")
async def oauth_callback(request: Request) -> HTMLResponse:
    """Receives the authorization code from Google and exchanges it for tokens."""
    _purge_expired()
    query = request.query_params
    flow_token = query.get("state") or ""
    session = _sessions_by_flow.get(flow_token)

    if session is None:
        return _page(
            "Sesja wygasła",
            "Nie znaleziono sesji logowania. Spróbuj ponownie na telewizorze.",
            status_code=410,
        )

    error = query.get("error")
    if error:
        session.status = "error"
        session.error = error
        session.completed_at = time.time()
        return _page(
            "Logowanie anulowane",
            f"Google zwróciło błąd: {html.escape(error)}. Możesz spróbować ponownie na telewizorze.",
            status_code=400,
        )

    code = query.get("code")
    if not code:
        session.status = "error"
        session.error = "missing_code"
        session.completed_at = time.time()
        return _page("Błąd", "Brak kodu autoryzacji w odpowiedzi.", status_code=400)

    try:
        token = await _exchange_code(code, session.code_verifier)
    except Exception as exc:  # surfaced to the TV through the poll endpoint
        session.status = "error"
        session.error = str(exc)[:300]
        session.completed_at = time.time()
        return _page("Błąd wymiany tokenu", html.escape(str(exc)), status_code=502)

    session.access_token = token.get("access_token")
    session.refresh_token = token.get("refresh_token")
    session.expires_in = token.get("expires_in")
    session.scope = token.get("scope")
    session.status = "ready"
    session.completed_at = time.time()

    return _page(
        "Gotowe!",
        "Możesz wrócić do telewizora. Logowanie zostało zakończone.",
    )


@app.get("/privacy")
async def privacy() -> HTMLResponse:
    return _page(
        "Polityka prywatności",
        "Usługa służy wyłącznie do przekazania telewizorowi tokenu dostępu do Google Photos. "
        "Nie przechowuje zdjęć ani danych osobowych. Tokeny są trzymane w pamięci przez kilka minut "
        "i usuwane po odebraniu ich przez telewizor. Logi zawierają wyłącznie kody błędów.",
    )


@app.get("/")
async def index() -> HTMLResponse:
    if not _configured():
        return _page("Konfiguracja", "Usługa nie jest jeszcze skonfigurowana.", status_code=503)
    return _page("Photo Gallery Screensaver", "Usługa logowania działa.")


# --- Helpers ----------------------------------------------------------------------------


def _pkce_pair_from_verifier(verifier: str) -> tuple[str, str]:
    digest = hashlib.sha256(verifier.encode()).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode()
    return verifier, challenge


def _url_with_params(base: str, params: dict[str, str]) -> str:
    from urllib.parse import urlencode

    return f"{base}?{urlencode(params)}"


async def _exchange_code(code: str, verifier: str) -> dict:
    assert _http is not None
    response = await _http.post(
        GOOGLE_TOKEN_ENDPOINT,
        data={
            "code": code,
            "client_id": GOOGLE_CLIENT_ID,
            "client_secret": GOOGLE_CLIENT_SECRET,
            "redirect_uri": REDIRECT_URI,
            "grant_type": "authorization_code",
            "code_verifier": verifier,
        },
    )
    if response.status_code != 200:
        raise RuntimeError(f"token_exchange_failed ({response.status_code}) {response.text[:200]}")
    return response.json()


async def _refresh_token(refresh_token: str) -> dict:
    assert _http is not None
    response = await _http.post(
        GOOGLE_TOKEN_ENDPOINT,
        data={
            "client_id": GOOGLE_CLIENT_ID,
            "client_secret": GOOGLE_CLIENT_SECRET,
            "refresh_token": refresh_token,
            "grant_type": "refresh_token",
        },
    )
    if response.status_code != 200:
        # Google returns invalid_grant here when the user revoked access.
        raise RuntimeError(f"refresh_failed ({response.status_code}) {response.text[:200]}")
    return response.json()


def _page(title: str, message: str, status_code: int = 200) -> HTMLResponse:
    document = f"""<!doctype html>
<html lang="pl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<style>
  :root {{ color-scheme: dark; }}
  body {{
    margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
    background: #10131a; color: #e8ecf3;
    font: 16px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  }}
  main {{ max-width: 30rem; padding: 2rem; text-align: center; }}
  h1 {{ font-size: 1.5rem; margin: 0 0 1rem; }}
  p {{ margin: 0; color: #aab4c4; }}
</style>
</head>
<body>
<main>
  <h1>{html.escape(title)}</h1>
  <p>{message}</p>
</main>
</body>
</html>"""
    return HTMLResponse(document, status_code=status_code)
