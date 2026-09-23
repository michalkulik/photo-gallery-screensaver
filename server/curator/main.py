"""Daily curator for the Synology Photos album "ostatnie_najciekawsze".

Once a day it replaces the album contents with one photo per day from the last 90 days, never
reusing a photo it has already picked. Photos that have been used are remembered in SQLite.

The service is deliberately small and dependency-light: FastAPI for the small web UI, httpx for
the NAS calls, SQLite for state. It has no scheduler library - a background task checks the clock
once a minute, which is plenty for a daily job and avoids another dependency.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from contextlib import asynccontextmanager
from datetime import date, datetime, time
from pathlib import Path
from typing import Any, Optional

from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from curator import Candidate, Store, cutoff_for, day_span, select_daily
from syno import SynoError, SynoPhotos, TwoFactorRequired, describe_error

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
)
log = logging.getLogger("curator")

STATE_DIR = Path(os.environ.get("CURATOR_STATE_DIR", "/var/lib/photo-curator"))
CONFIG_PATH = STATE_DIR / "config.json"
DB_PATH = STATE_DIR / "curator.db"

DEFAULTS: dict[str, Any] = {
    "host": os.environ.get("SYNO_HOST", ""),
    "port": int(os.environ.get("SYNO_PORT", "5001")),
    "secure": os.environ.get("SYNO_SECURE", "true").lower() != "false",
    "verify_tls": os.environ.get("SYNO_VERIFY_TLS", "false").lower() == "true",
    "account": os.environ.get("SYNO_ACCOUNT", ""),
    "password": os.environ.get("SYNO_PASSWORD", ""),
    "device_id": os.environ.get("SYNO_DEVICE_ID", ""),
    "album": os.environ.get("CURATOR_ALBUM", "ostatnie_najciekawsze"),
    "shared_space": os.environ.get("CURATOR_SHARED_SPACE", "true").lower() != "false",
    "days": int(os.environ.get("CURATOR_DAYS", "90")),
    "run_hour": int(os.environ.get("CURATOR_RUN_HOUR", "4")),
    "max_window_days": int(os.environ.get("CURATOR_MAX_WINDOW_DAYS", "3650")),
    "dry_run": os.environ.get("CURATOR_DRY_RUN", "false").lower() == "true",
}

_store: Optional[Store] = None
_run_lock = asyncio.Lock()
_last_run_date: Optional[date] = None


# --- Configuration -------------------------------------------------------------------------


def load_config() -> dict[str, Any]:
    config = dict(DEFAULTS)
    if CONFIG_PATH.exists():
        try:
            config.update(json.loads(CONFIG_PATH.read_text()))
        except (OSError, ValueError) as error:
            log.warning("cannot read %s: %s", CONFIG_PATH, error)
    # Environment always wins, so a password can be supplied without writing it to disk.
    for key, env in (("password", "SYNO_PASSWORD"), ("device_id", "SYNO_DEVICE_ID"),
                     ("account", "SYNO_ACCOUNT"), ("host", "SYNO_HOST")):
        if os.environ.get(env):
            config[key] = os.environ[env]
    return config


def save_config(config: dict[str, Any]) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    # The file holds the NAS password, so it must not be world readable.
    CONFIG_PATH.touch(mode=0o600, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(config, indent=2))
    os.chmod(CONFIG_PATH, 0o600)


def store() -> Store:
    assert _store is not None, "store not initialised"
    return _store


# --- The daily job -------------------------------------------------------------------------


def run_curation(config: dict[str, Any]) -> dict[str, Any]:
    """Replaces the album contents with a fresh daily selection."""
    if not config["host"] or not config["account"]:
        raise SynoError("the NAS address and account are not configured")
    if not config["password"] and not config["device_id"]:
        raise SynoError("no password or device token is configured")

    album_name = config["album"]
    days = int(config["days"])
    run_id = store().start_run()
    log.info("run %s started (album=%s, days=%s)", run_id, album_name, days)

    client = SynoPhotos(
        host=config["host"], port=int(config["port"]), secure=bool(config["secure"]),
        verify_tls=bool(config["verify_tls"]),
    )
    try:
        device_id = client.login(
            account=config["account"], password=config["password"],
            device_id=config["device_id"] or None,
        )
        if device_id and device_id != config["device_id"]:
            # Remembered so the next run needs no one-time password.
            config["device_id"] = device_id
            save_config(config)

        album = client.find_album(album_name)
        log.info("album %r id=%s shared=%s holds %s items",
                 album.name, album.id, album.shared, album.item_count)
        # Logged because whether an album lives in the shared or the personal space decides
        # which space the photos must be read from.
        for candidate in client.albums():
            log.info("  album %-32s id=%-6s shared=%-5s items=%s",
                     candidate.name, candidate.id, candidate.shared, candidate.item_count)

        used = store().used_ids()
        log.info("%s photos have been used before", len(used))

        # Widen the window until enough days with unused photos are found. This is the
        # "walk further back" behaviour: an empty day, or one whose photos were all used,
        # simply does not count towards the total.
        window = days
        selection = None
        while window <= int(config["max_window_days"]):
            since = datetime.combine(cutoff_for(window), time.min).astimezone()
            items = client.items_since(since, shared_space=bool(config["shared_space"]))
            candidates = [
                Candidate(item_id=item.id, filename=item.filename, taken_on=day_span(item.taken_at))
                for item in items
                if not item.is_video
            ]
            selection = select_daily(candidates, used, days)
            log.info(
                "window %sd: %s candidates, %s days available, selected %s/%s",
                window, len(candidates), selection.days_available, len(selection.chosen), days,
            )
            if len(selection.chosen) >= days:
                break
            window *= 2

        if selection is None or not selection.chosen:
            message = "no photos could be selected"
            store().finish_run(run_id, "error", 0, 0, window, message)
            raise SynoError(message)

        selected_ids = [c.item_id for c in selection.chosen]
        if config["dry_run"]:
            log.info("dry run: would set %s photos", len(selected_ids))
            store().finish_run(run_id, "dry-run", len(selected_ids), 0, window)
            return {"selected": len(selected_ids), "removed": 0, "window_days": window,
                    "dry_run": True}

        # Add first, then remove. If adding fails the album is left exactly as it was; the other
        # order would empty it and leave nothing behind. During the swap the album briefly holds
        # both sets, which is harmless.
        current = client.album_item_ids(album.id)
        log.info("album currently holds %s items", len(current))
        client.add_to_album(album.id, selected_ids)
        log.info("added %s items to the album", len(selected_ids))

        stale = [item_id for item_id in current if item_id not in set(selected_ids)]
        client.remove_from_album(album.id, stale)
        log.info("removed %s items that are no longer part of the selection", len(stale))

        store().mark_used(selection.chosen, date.today())
        store().finish_run(run_id, "ok", len(selected_ids), len(stale), window)
        log.info("run %s finished", run_id)
        return {"selected": len(selected_ids), "removed": len(stale), "window_days": window}

    except TwoFactorRequired as error:
        store().finish_run(run_id, "error", 0, 0, 0, "two-factor code required")
        log.error("the NAS asked for a two-factor code: %s", error)
        raise
    except SynoError as error:
        store().finish_run(run_id, "error", 0, 0, 0, str(error))
        log.error("run %s failed: %s", run_id, error)
        raise
    except Exception as error:
        # Anything unexpected must still close the run row, otherwise it stays "running" and
        # the history becomes misleading.
        store().finish_run(run_id, "error", 0, 0, 0, f"{type(error).__name__}: {error}")
        log.exception("run %s failed unexpectedly", run_id)
        raise
    finally:
        client.close()


async def scheduler_loop() -> None:
    """Runs the curation once a day at the configured hour."""
    global _last_run_date
    while True:
        try:
            config = load_config()
            now = datetime.now()
            if now.hour == int(config["run_hour"]) and _last_run_date != now.date():
                _last_run_date = now.date()
                log.info("daily run starting")
                async with _run_lock:
                    await asyncio.to_thread(run_curation, config)
        except Exception as error:  # keep the loop alive whatever happens
            log.error("scheduled run failed: %s", error)
        await asyncio.sleep(60)


@asynccontextmanager
async def lifespan(_: FastAPI):
    global _store
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    _store = Store(str(DB_PATH))
    task = asyncio.create_task(scheduler_loop())
    try:
        yield
    finally:
        task.cancel()
        _store.close()
        _store = None


app = FastAPI(title="Synology Photos curator", docs_url=None, redoc_url=None, lifespan=lifespan)


# --- Pages and API -------------------------------------------------------------------------


@app.get("/health")
async def health() -> JSONResponse:
    config = load_config()
    return JSONResponse({
        "status": "ok",
        "configured": bool(config["host"] and config["account"]
                           and (config["password"] or config["device_id"])),
        "album": config["album"],
        "days": config["days"],
        "used_photos": store().used_count(),
    })


@app.get("/api/status")
async def status() -> JSONResponse:
    config = load_config()
    return JSONResponse({
        "album": config["album"],
        "days": config["days"],
        "run_hour": config["run_hour"],
        "host": config["host"],
        "account": config["account"],
        "has_password": bool(config["password"]),
        "has_device_token": bool(config["device_id"]),
        "shared_space": config["shared_space"],
        "dry_run": config["dry_run"],
        "used_photos": store().used_count(),
        "last_runs": store().last_runs(10),
    })


@app.post("/api/run")
async def run_now() -> JSONResponse:
    """Runs the curation immediately, for testing."""
    if _run_lock.locked():
        return JSONResponse({"error": "a run is already in progress"}, status_code=409)
    try:
        async with _run_lock:
            result = await asyncio.to_thread(run_curation, load_config())
        return JSONResponse({"status": "ok", **result})
    except TwoFactorRequired:
        return JSONResponse(
            {"error": "the NAS requires a two-factor code; sign in again with a code"},
            status_code=401,
        )
    except SynoError as error:
        return JSONResponse({"error": str(error)}, status_code=502)
    except Exception as error:
        # A bare 500 tells the user nothing, and this endpoint is how they test the setup.
        log.exception("run failed unexpectedly")
        return JSONResponse({"error": f"{type(error).__name__}: {error}"}, status_code=500)


@app.post("/api/login")
async def login(
    host: str = Form(...),
    port: int = Form(5001),
    account: str = Form(...),
    password: str = Form(...),
    otp_code: str = Form(""),
    verify_tls: str = Form(""),
) -> RedirectResponse:
    """Signs in and stores the device token so later runs need no code."""
    config = load_config()
    config.update({
        "host": host.strip(),
        "port": int(port),
        "account": account.strip(),
        "password": password,
        "verify_tls": bool(verify_tls),
    })
    client = SynoPhotos(
        host=config["host"], port=config["port"], secure=config["secure"],
        verify_tls=config["verify_tls"],
    )
    try:
        device_id = client.login(
            account=config["account"], password=config["password"],
            otp_code=otp_code.strip() or None,
        )
        config["device_id"] = device_id
        save_config(config)
        log.info("signed in successfully; device token stored: %s", bool(device_id))
        return RedirectResponse("/?notice=signed-in", status_code=303)
    except TwoFactorRequired:
        return RedirectResponse("/?error=need-code", status_code=303)
    except SynoError as error:
        log.warning("sign-in failed: %s", error)
        return RedirectResponse(f"/?error={_slug(str(error))}", status_code=303)
    finally:
        client.close()


@app.post("/api/config")
async def update_config(
    album: str = Form(...),
    days: int = Form(90),
    run_hour: int = Form(4),
    shared_space: str = Form(""),
    dry_run: str = Form(""),
) -> RedirectResponse:
    config = load_config()
    config.update({
        "album": album.strip(),
        "days": max(1, int(days)),
        "run_hour": min(23, max(0, int(run_hour))),
        "shared_space": bool(shared_space),
        "dry_run": bool(dry_run),
    })
    save_config(config)
    return RedirectResponse("/?notice=saved", status_code=303)


@app.post("/api/forget")
async def forget() -> RedirectResponse:
    """Clears the used-photo history so every photo becomes available again."""
    removed = store().forget_all()
    log.info("cleared %s used photos", removed)
    return RedirectResponse(f"/?notice=forgotten-{removed}", status_code=303)


@app.get("/", response_class=HTMLResponse)
async def index(request: Request) -> HTMLResponse:
    config = load_config()
    status_data = json.loads((await status()).body)
    notice = request.query_params.get("notice")
    error = request.query_params.get("error")
    return HTMLResponse(_render(config, status_data, notice, error))


def _slug(text: str) -> str:
    return "".join(c if c.isalnum() or c in "-_ " else "" for c in text)[:160].replace(" ", "+")


def _render(config: dict[str, Any], status_data: dict[str, Any], notice: Optional[str],
            error: Optional[str]) -> str:
    rows = ""
    for run in status_data["last_runs"]:
        rows += (
            f"<tr><td>{run['id']}</td><td>{run['started_at']}</td>"
            f"<td class='{run['status']}'>{run['status']}</td>"
            f"<td>{run['selected']}</td><td>{run['removed']}</td>"
            f"<td>{run['window_days']}</td>"
            f"<td class='msg'>{_escape(str(run.get('message') or ''))}</td></tr>"
        )
    if not rows:
        rows = "<tr><td colspan='7'>No runs yet.</td></tr>"

    banner = ""
    if notice:
        banner = f"<p class='ok'>{_escape(notice)}</p>"
    if error:
        banner = f"<p class='bad'>Error: {_escape(error.replace('+', ' '))}</p>"

    token_state = "stored (no code needed)" if status_data["has_device_token"] else "not stored"
    password_state = "stored" if status_data["has_password"] else "not stored"

    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Synology Photos curator</title>
<style>
  :root {{ color-scheme: dark; }}
  body {{ margin:0; padding:2rem 1.25rem; background:#10131a; color:#e8ecf3;
         font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }}
  main {{ max-width:60rem; margin:0 auto; }}
  h1 {{ font-size:1.4rem; margin:0 0 .25rem; }}
  h2 {{ font-size:1rem; margin:2rem 0 .6rem; color:#9fb0c8; text-transform:uppercase;
        letter-spacing:.06em; }}
  .sub {{ color:#8b97a8; margin:0 0 1.5rem; }}
  form {{ display:grid; grid-template-columns:14rem 1fr; gap:.6rem 1rem; align-items:center;
          max-width:38rem; }}
  label {{ color:#aab4c4; }}
  input[type=text],input[type=password],input[type=number] {{
    background:#181d27; border:1px solid #2a3242; color:#e8ecf3; padding:.45rem .6rem;
    border-radius:.35rem; font:inherit; width:100%; }}
  input[type=checkbox] {{ width:1.1rem; height:1.1rem; }}
  button {{ grid-column:2; justify-self:start; background:#2f6feb; color:#fff; border:0;
            padding:.55rem 1.1rem; border-radius:.35rem; font:inherit; cursor:pointer; }}
  button.ghost {{ background:#232a36; }}
  table {{ border-collapse:collapse; width:100%; font-size:.88rem; }}
  th,td {{ text-align:left; padding:.4rem .6rem; border-bottom:1px solid #222a36; }}
  th {{ color:#8b97a8; font-weight:600; }}
  td.ok {{ color:#5ec27b; }} td.error {{ color:#e5695f; }} td.dry-run {{ color:#d0a44c; }}
  td.msg {{ color:#8b97a8; max-width:22rem; overflow-wrap:anywhere; }}
  p.ok {{ color:#5ec27b; }} p.bad {{ color:#e5695f; }}
  .row {{ display:flex; gap:.6rem; flex-wrap:wrap; }}
  .meta {{ color:#8b97a8; font-size:.9rem; }}
</style></head><body><main>
<h1>Synology Photos curator</h1>
<p class="sub">Replaces the album <strong>{_escape(config['album'])}</strong> with one photo per day
from the last {config['days']} days, never reusing a photo. Runs daily at
{int(config['run_hour']):02d}:00.</p>
{banner}

<h2>Status</h2>
<p class="meta">
  NAS: {_escape(status_data['host'] or '—')} &nbsp;·&nbsp;
  account: {_escape(status_data['account'] or '—')} &nbsp;·&nbsp;
  password {password_state} &nbsp;·&nbsp; device token {token_state} &nbsp;·&nbsp;
  photos used so far: <strong>{status_data['used_photos']}</strong>
</p>
<div class="row">
  <form method="post" action="/api/run" style="display:inline">
    <button type="submit">Run now</button>
  </form>
  <form method="post" action="/api/forget" style="display:inline"
        onsubmit="return confirm('Forget every used photo? All photos become available again.')">
    <button class="ghost" type="submit">Forget used photos</button>
  </form>
</div>

<h2>Sign in to the NAS</h2>
<form method="post" action="/api/login">
  <label for="host">NAS address</label>
  <input id="host" name="host" type="text" value="{_escape(config['host'])}" required>
  <label for="port">Port</label>
  <input id="port" name="port" type="number" value="{config['port']}" required>
  <label for="account">Account</label>
  <input id="account" name="account" type="text" value="{_escape(config['account'])}" required>
  <label for="password">Password</label>
  <input id="password" name="password" type="password" required>
  <label for="otp">Two-factor code</label>
  <input id="otp" name="otp_code" type="text" inputmode="numeric"
         placeholder="leave empty if not enabled">
  <label for="verify">Verify TLS certificate</label>
  <input id="verify" name="verify_tls" type="checkbox" {'checked' if config['verify_tls'] else ''}>
  <button type="submit">Sign in and store device token</button>
</form>
<p class="meta">The code is only needed once: the NAS then issues a device token that lets the
daily run sign in unattended.</p>

<h2>Selection</h2>
<form method="post" action="/api/config">
  <label for="album">Album name</label>
  <input id="album" name="album" type="text" value="{_escape(config['album'])}" required>
  <label for="days">Photos (one per day)</label>
  <input id="days" name="days" type="number" min="1" value="{config['days']}" required>
  <label for="hour">Run at hour</label>
  <input id="hour" name="run_hour" type="number" min="0" max="23" value="{config['run_hour']}" required>
  <label for="shared">Use the shared space</label>
  <input id="shared" name="shared_space" type="checkbox" {'checked' if config['shared_space'] else ''}>
  <label for="dry">Dry run (do not change the album)</label>
  <input id="dry" name="dry_run" type="checkbox" {'checked' if config['dry_run'] else ''}>
  <button type="submit">Save</button>
</form>

<h2>Recent runs</h2>
<table>
  <tr><th>#</th><th>Started</th><th>Status</th><th>Added</th><th>Removed</th><th>Window</th>
      <th>Message</th></tr>
  {rows}
</table>
</main></body></html>"""


def _escape(text: str) -> str:
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;"))
