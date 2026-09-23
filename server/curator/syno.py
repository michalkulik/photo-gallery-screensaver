"""Client for the Synology Photos WebAPI.

Written against the API surface the DiskStation advertises through `SYNO.API.Info`, a public
discovery endpoint, so it does not depend on any Synology code.

Behaviour that is easy to get wrong, and is handled here:

* Over HTTPS the password must be sent **in the clear**. DSM compares `passwd` literally when TLS
  already protects it; an RSA-wrapped blob is rejected as a wrong password.
* The device token lives in the response field ``device_id`` (older builds used ``did``). Storing
  it lets later sign-ins skip the one-time password.
* `enable_syno_token` must **not** be sent. Asking for it makes DSM demand an ``X-SYNO-TOKEN``
  header on every later call, and without it everything fails with 119 "session expired".
* Item listings are paginated and report no total, so paging stops on an empty page.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

import httpx

log = logging.getLogger("curator.syno")

# Synology answers every call with this envelope.
ENVELOPE_SUCCESS = "success"

# Error codes worth naming.
CODE_BAD_CREDENTIALS = 400
CODE_TWO_FACTOR_REQUIRED = 403
CODE_AUTO_BLOCKED = 407
CODE_SESSION_EXPIRED = 119
CODE_SESSION_INVALID = 120

PAGE_SIZE = 200
# Safety valve so a misbehaving NAS cannot spin a paging loop forever.
MAX_PAGES = 500


class SynoError(RuntimeError):
    """A Synology call failed. The message always names the API and the error code."""

    def __init__(self, message: str, code: Optional[int] = None) -> None:
        super().__init__(message)
        self.code = code


class TwoFactorRequired(SynoError):
    """The account needs a one-time password before it can sign in."""


@dataclass
class Album:
    id: int
    name: str
    item_count: int
    shared: bool


@dataclass
class Item:
    id: int
    filename: str
    taken_at: int
    is_video: bool


class SynoPhotos:
    """A logged-in Synology Photos session."""

    def __init__(self, host: str, port: int = 5001, secure: bool = True, verify_tls: bool = False,
                 timeout: float = 30.0) -> None:
        scheme = "https" if secure else "http"
        clean = host.strip().removeprefix("http://").removeprefix("https://").rstrip("/")
        self.base_url = f"{scheme}://{clean}:{port}"
        self._client = httpx.Client(verify=verify_tls, timeout=timeout)
        self.sid: Optional[str] = None
        self.device_id: Optional[str] = None

    # --- Session ---------------------------------------------------------------------------

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> "SynoPhotos":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def login(self, account: str, password: str, otp_code: Optional[str] = None,
              device_id: Optional[str] = None, device_name: str = "Photo Curator") -> str:
        """Signs in and returns the device token, if the NAS issued one."""
        params: dict[str, str] = {
            "api": "SYNO.API.Auth",
            "version": "7",
            "method": "login",
            "account": account,
            # Sent in the clear: DSM wants the literal password over HTTPS.
            "passwd": password,
            "session": "Photos",
            "format": "sid",
            "logintype": "local",
        }
        if otp_code:
            params["otp_code"] = otp_code.strip()
        if device_id:
            params["device_id"] = device_id.strip()
        # Ask for a device token so the daily run needs no code. Only meaningful with a code.
        if otp_code and not device_id:
            params["enable_device_token"] = "yes"
            params["device_name"] = device_name

        data = self._call_raw("entry.cgi", params)
        self.sid = data.get("sid")
        if not self.sid:
            raise SynoError("login succeeded without a session id")
        self.device_id = data.get("device_id") or data.get("did")
        log.info("signed in as %s (device token: %s)", account, "yes" if self.device_id else "no")
        return self.device_id or ""

    def logout(self) -> None:
        if not self.sid:
            return
        try:
            self._get("entry.cgi", {"api": "SYNO.API.Auth", "version": "7", "method": "logout",
                                    "session": "Photos"})
        except SynoError:
            pass
        finally:
            self.sid = None

    # --- Albums ----------------------------------------------------------------------------

    def albums(self) -> list[Album]:
        """Lists albums, including those in the shared space (flagged ``shared``)."""
        data = self._api("SYNO.Foto.Browse.Album", 5, "list", {"offset": 0, "limit": 500})
        result: list[Album] = []
        for raw in data.get("list") or []:
            album_id = raw.get("id") or 0
            if not album_id:
                continue
            result.append(Album(
                id=int(album_id),
                name=raw.get("name") or f"album-{album_id}",
                item_count=int(raw.get("item_count") or 0),
                shared=bool(raw.get("shared")),
            ))
        return result

    def find_album(self, name: str) -> Album:
        """Finds an album by name, preferring the shared space when the name is ambiguous."""
        matches = [a for a in self.albums() if a.name == name]
        if not matches:
            available = ", ".join(sorted(a.name for a in self.albums())) or "(none)"
            raise SynoError(f"album {name!r} not found; available: {available}")
        shared = [a for a in matches if a.shared]
        return shared[0] if shared else matches[0]

    def album_item_ids(self, album_id: int) -> list[int]:
        """Every item currently in the album."""
        return [item.id for item in self.album_items(album_id)]

    def album_items(self, album_id: int) -> list[Item]:
        """Every item currently in the album.

        The album is selected with ``album_id``. Passing ``id`` instead is accepted by DSM but
        ignored, which silently returns the whole library - and then a "remove what is stale"
        step would try to remove thousands of photos that were never in the album.
        """
        items: list[Item] = []
        offset = 0
        for _ in range(MAX_PAGES):
            data = self._api(
                "SYNO.Foto.Browse.Item", 6, "list",
                {
                    "offset": offset,
                    "limit": PAGE_SIZE,
                    "sort_by": "takentime",
                    "sort_direction": "desc",
                    "additional": json.dumps(["thumbnail"]),
                    "album_id": album_id,
                },
            )
            batch = _parse_items(data)
            items.extend(batch)
            if not batch:
                break
            offset += len(batch)
        return items

    def add_to_album(self, album_id: int, item_ids: list[int]) -> None:
        """Adds items to an album. Existing members are left alone by DSM."""
        if not item_ids:
            return
        self._api(
            "SYNO.Foto.Browse.NormalAlbum", 4, "add_item",
            {"id": album_id, "item": json.dumps(item_ids)},
            post=True,
        )

    def remove_from_album(self, album_id: int, item_ids: list[int]) -> None:
        """Removes items from an album. The photos themselves are not deleted."""
        if not item_ids:
            return
        self._api(
            "SYNO.Foto.Browse.NormalAlbum", 4, "delete_item",
            {"id": album_id, "item": json.dumps(item_ids)},
            post=True,
        )

    # --- Photos ----------------------------------------------------------------------------

    def items_since(self, since: datetime, shared_space: bool = True,
                    max_items: int = 20000) -> list[Item]:
        """Lists photos taken since ``since``, newest first.

        Stops as soon as an item falls before the cutoff, which keeps a large library cheap:
        the listing is sorted by capture time, so nothing older can follow.
        """
        cutoff = int(since.timestamp())
        api = "SYNO.FotoTeam.Browse.Item" if shared_space else "SYNO.Foto.Browse.Item"
        items: list[Item] = []
        offset = 0
        for _ in range(MAX_PAGES):
            data = self._api(
                api, 6, "list",
                {
                    "offset": offset,
                    "limit": PAGE_SIZE,
                    "sort_by": "takentime",
                    "sort_direction": "desc",
                    "additional": json.dumps(["thumbnail"]),
                },
            )
            batch = _parse_items(data)
            if not batch:
                break
            for item in batch:
                if item.taken_at and item.taken_at < cutoff:
                    # Sorted descending, so everything after this is older too.
                    return items
                items.append(item)
                if len(items) >= max_items:
                    return items
            offset += len(batch)
        return items

    # --- HTTP ------------------------------------------------------------------------------

    def _api(self, api: str, version: int, method: str, params: dict[str, Any],
             post: bool = False) -> dict[str, Any]:
        body = {"api": api, "version": str(version), "method": method}
        for key, value in params.items():
            body[key] = value if isinstance(value, str) else json.dumps(value)
        try:
            return self._call("entry.cgi", body, post=post)
        except SynoError as error:
            # Naming the API matters: a bare code does not say which call failed.
            raise SynoError(f"{api}.{method}: {error}", error.code) from error

    def _get(self, path: str, params: dict[str, Any]) -> dict[str, Any]:
        return self._call(path, params, post=False)

    def _call(self, path: str, params: dict[str, Any], post: bool) -> dict[str, Any]:
        if self.sid:
            params = {**params, "_sid": self.sid}
        url = f"{self.base_url}/webapi/{path}"
        # Mutations are sent as a POST body: an album can hold thousands of items, and the id
        # list does not fit in a query string.
        if post:
            response = self._client.post(url, data=params)
        else:
            response = self._client.get(url, params=params)
        if response.status_code != 200:
            raise SynoError(f"HTTP {response.status_code} from {url}")
        try:
            payload = response.json()
        except ValueError as error:
            raise SynoError(f"non-JSON reply from {path}: {response.text[:200]}") from error

        if not payload.get(ENVELOPE_SUCCESS):
            error = payload.get("error") or {}
            code = int(error.get("code") or -1)
            # DSM names the offending parameter in `errors`; it is the fastest way to diagnose.
            detail = error.get("errors")
            message = f"Synology error {code}" + (f" ({detail})" if detail else "")
            if code == CODE_TWO_FACTOR_REQUIRED:
                raise TwoFactorRequired(message, code)
            raise SynoError(message, code)

        data = payload.get("data")
        return data if isinstance(data, dict) else {}

    def _call_raw(self, path: str, params: dict[str, Any]) -> dict[str, Any]:
        return self._call(path, params, post=False)


def _parse_items(data: dict[str, Any]) -> list[Item]:
    result: list[Item] = []
    for raw in data.get("list") or []:
        item_id = raw.get("id") or 0
        if not item_id:
            continue
        thumbnail = (raw.get("additional") or {}).get("thumbnail") or {}
        result.append(Item(
            id=int(item_id),
            filename=thumbnail.get("original_name") or f"item-{item_id}",
            taken_at=int(raw.get("time") or 0),
            is_video=_is_video(raw.get("type")),
        ))
    return result


def _is_video(raw_type: Any) -> bool:
    """Reads the item type, which is a string in the shared space and a number elsewhere.

    Both representations are accepted, including a number arriving as a string, because the two
    spaces disagree and a wrong answer would silently drop every video from the selection.
    """
    if isinstance(raw_type, str):
        text = raw_type.strip().lower()
        if not text:
            return False
        if text.lstrip("-").isdigit():
            return int(text) != 0
        return text in {"video", "live_video", "live"}
    try:
        return int(raw_type or 0) != 0
    except (TypeError, ValueError):
        return False


def describe_error(code: int) -> str:
    """Turns a Synology error code into something a person can act on."""
    return {
        CODE_BAD_CREDENTIALS: "wrong account or password",
        CODE_TWO_FACTOR_REQUIRED: "a two-factor code is required",
        CODE_AUTO_BLOCKED: "this host is blocked by DSM Auto Block",
        CODE_SESSION_EXPIRED: "the session expired",
        CODE_SESSION_INVALID: "the session is no longer valid",
    }.get(code, f"unexpected error {code}")


def local_day(timestamp: int) -> str:
    """The calendar day of a capture time, in the server's timezone."""
    return datetime.fromtimestamp(timestamp, tz=timezone.utc).astimezone().strftime("%Y-%m-%d")


def days_ago(days: int, now: Optional[datetime] = None) -> datetime:
    reference = now or datetime.now().astimezone()
    return reference - timedelta(days=days)
