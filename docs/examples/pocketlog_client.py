#!/usr/bin/env python3
"""Minimal private PocketLog API client (Python 3.9+ standard library only).

Demonstrates the /v1 integration contract from docs/API.md: capability detection,
a complete paged activity read, search, a category correction and safe retries.
It reads only derived cards; raw observations and screenshots are not readable.

    python3 pocketlog_client.py https://your-private-host:12446 status
    python3 pocketlog_client.py https://your-private-host:12446 read 2026-11-16T04:00+08:00 2026-11-17T04:00+08:00
    python3 pocketlog_client.py https://your-private-host:12446 read FROM TO --q report --category work
    python3 pocketlog_client.py https://your-private-host:12446 correct ACTIVITY_ID work "New title" "Optional summary"

The key is prompted without echo (or read from standard input when piped). Use a
revocable journal device key, never a key in the URL, command line or a log.
"""
import argparse
import datetime
import getpass
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

RETRYABLE = {408, 425, 429, 500, 502, 503, 504}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """A redirect could forward the bearer key to another origin; refuse it."""

    def redirect_request(self, *args, **kwargs):
        return None


class ApiError(Exception):
    def __init__(self, status, error):
        super().__init__(f"HTTP {status}: {error}")
        self.status = status
        self.error = error


class Client:
    def __init__(self, origin, key, *, allow_loopback_http=False, attempts=4):
        parsed = urllib.parse.urlsplit(origin)
        loopback = parsed.hostname in {"127.0.0.1", "::1", "localhost"}
        if parsed.scheme != "https" and not (allow_loopback_http and loopback and parsed.scheme == "http"):
            raise ValueError("origin must use https://")
        if parsed.username or parsed.password or parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
            raise ValueError("origin must be scheme://host[:port] only")
        if len(key) != 64 or any(c not in "0123456789abcdefABCDEF" for c in key):
            raise ValueError("expected a 64-character hexadecimal device key")
        self._origin = f"{parsed.scheme}://{parsed.netloc}"
        self._key = key
        self._attempts = attempts
        self._opener = urllib.request.build_opener(NoRedirect)

    def request(self, method, path, body=None, query=None):
        url = self._origin + path + ("?" + urllib.parse.urlencode(query) if query else "")
        data = json.dumps(body).encode() if body is not None else None
        for attempt in range(self._attempts):
            req = urllib.request.Request(url, data=data, method=method)
            req.add_header("Authorization", "Bearer " + self._key)
            req.add_header("Accept", "application/json")
            if data is not None:
                req.add_header("Content-Type", "application/json")
            try:
                with self._opener.open(req, timeout=15) as response:
                    raw = response.read(512 * 1024 + 1)
                    if len(raw) > 512 * 1024:
                        raise ApiError(response.status, "response exceeded 512 KiB")
                    return json.loads(raw) if raw else None
            except urllib.error.HTTPError as failure:
                try:
                    error = json.loads(failure.read(4096)).get("error", "")
                except ValueError:
                    error = ""  # Servers without the json_errors capability.
                # Every write below is idempotent (stable IDs / full replacement),
                # so retrying a transient refusal cannot duplicate journal data.
                if failure.code in RETRYABLE and attempt + 1 < self._attempts:
                    time.sleep(retry_delay(failure.headers.get("Retry-After"), attempt))
                    continue
                raise ApiError(failure.code, error) from None
        raise AssertionError("unreachable")

    def capabilities(self):
        status = self.request("GET", "/v1/status")
        return set(status.get("api", {}).get("capabilities", []))

    def read_activities(self, from_ms, to_ms, q=None, category=None, limit=200):
        """Yields every matching card exactly once, in stored start order.

        Raises ApiError(409, "cursor expired") if the journal is deleted mid-read;
        cards yielded before that belong to the deleted journal and must be discarded.
        """
        if "activity_pages" not in self.capabilities():
            raise ApiError(0, "server predates GET /v1/activities; use /v1/timeline windows")
        query = {"from_ms": from_ms, "to_ms": to_ms, "limit": limit}
        if q:
            query["q"] = q
        if category:
            query["category"] = category
        cursor = {}
        while True:
            page = self.request("GET", "/v1/activities", query={**query, **cursor})
            yield from page["cards"]
            nxt = page["next_cursor"]
            if nxt is None:
                return
            cursor = {"after_start_ms": nxt["after_start_ms"], "after_id": nxt["after_id"], "epoch": page["journal_epoch"]}

    def correct(self, activity_id, category, title, summary=""):
        """A user correction replaces the card; repeating it is safe and wins over the worker."""
        self.request("POST", "/v1/cards", {"id": activity_id, "title": title, "summary": summary, "category": category})


def retry_delay(header, attempt):
    try:
        return min(max(int(header), 1), 60)
    except (TypeError, ValueError):
        return min(2 ** attempt, 30)


def to_ms(text):
    moment = datetime.datetime.fromisoformat(text)
    if moment.tzinfo is None:
        raise SystemExit("include a UTC offset, e.g. 2026-11-17T04:00+08:00")
    return int(moment.timestamp() * 1000)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("origin")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status")
    read = commands.add_parser("read")
    read.add_argument("start")
    read.add_argument("end")
    read.add_argument("--q")
    read.add_argument("--category")
    fix = commands.add_parser("correct")
    fix.add_argument("activity_id")
    fix.add_argument("category")
    fix.add_argument("title")
    fix.add_argument("summary", nargs="?", default="")
    args = parser.parse_args()
    key = getpass.getpass("Journal device key: ") if sys.stdin.isatty() else sys.stdin.readline().strip()
    client = Client(args.origin, key)
    del key
    try:
        if args.command == "status":
            print(json.dumps(client.request("GET", "/v1/status"), indent=2))
        elif args.command == "read":
            for card in client.read_activities(to_ms(args.start), to_ms(args.end), args.q, args.category):
                print(json.dumps(card, ensure_ascii=False))
        else:
            client.correct(args.activity_id, args.category, args.title, args.summary)
            print("saved")
    except ApiError as failure:
        hint = " (missing, expired, revoked or out-of-scope key)" if failure.status == 401 else ""
        raise SystemExit(f"{failure}{hint}")


if __name__ == "__main__":
    main()
