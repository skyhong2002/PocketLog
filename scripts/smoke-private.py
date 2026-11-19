#!/usr/bin/env python3
"""Disposable API/worker Docker smoke. Never touches the production journal volume."""
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def main():
    image = os.environ.get("POCKETLOG_SMOKE_IMAGE", "pocketlog:implementation-check")
    suffix = secrets.token_hex(6)
    volume = "pocketlog-smoke-" + suffix
    config_volume = volume + "-config"
    server = "pocketlog-smoke-api-" + suffix
    worker = "pocketlog-smoke-worker-" + suffix
    token = secrets.token_hex(32)
    with tempfile.TemporaryDirectory(prefix="pocketlog-smoke-") as folder:
        token_file = Path(folder) / "owner_token"
        token_file.write_text(token)
        token_file.chmod(0o444)  # Only this disposable random token, not production credentials.
        run("docker", "volume", "create", volume)
        run("docker", "volume", "create", config_volume)
        try:
            run("docker", "run", "-d", "--name", server, "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "256m", "-p", "127.0.0.1::8080", "-v", volume + ":/data", "-v", config_volume + ":/config", "-e", "POCKETLOG_AI_CONFIG_DIR=/config", "-v", str(token_file) + ":/run/secrets/owner_token:ro", image)
            port = run("docker", "port", server, "8080/tcp").rsplit(":", 1)[1]
            origin = "http://127.0.0.1:" + port

            def request(method, path, body=None, authorized=True):
                headers = {"Content-Type": "application/json"}
                if authorized:
                    headers["Authorization"] = "Bearer " + token
                req = urllib.request.Request(origin + path, data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
                with urllib.request.urlopen(req, timeout=5) as response:
                    assert response.headers["Cache-Control"] == "no-store"
                    raw = response.read()
                    return json.loads(raw) if raw else None

            for attempt in range(30):
                try:
                    request("GET", "/healthz", authorized=False)
                    break
                except (OSError, urllib.error.URLError):
                    time.sleep(0.2)
            else:
                raise AssertionError("server did not become healthy")
            try:
                request("GET", "/v1/status", authorized=False)
                raise AssertionError("unauthorized journal access")
            except urllib.error.HTTPError as error:
                assert error.code == 401
            settings = request("GET", "/v1/workspace")
            settings["onboarding_version"] = 1
            assert request("PUT", "/v1/workspace", settings)["revision"] == 1
            assert request("GET", "/v1/ai")["available"]
            # Save a disabled tombstone to exercise the worker's read-only config mount.
            request("DELETE", "/v1/ai")
            with urllib.request.urlopen(origin + "/", timeout=5) as page:
                assert b"PocketLog" in page.read()
                assert "frame-ancestors 'none'" in page.headers["Content-Security-Policy"]
            now = int(time.time() * 1000)
            activity = {"id": "smoke-session", "start_ms": now - 3_600_000, "end_ms": now - 1_800_000, "app": "org.example.reader", "category": "learning"}
            batch = {"activities": [activity]}
            assert request("POST", "/v1/activities", batch)["accepted"] == 1
            assert request("POST", "/v1/activities", batch)["accepted"] == 0
            observation = {"id": "smoke-context", "at_ms": now - 3_000_000, "app": activity["app"], "text": "Disposable reference material", "analysis_consent": True}
            request("POST", "/v1/observations", observation)
            overview = request("GET", f"/v1/overview?from_ms={now - 7_200_000}&to_ms={now}")
            assert overview["categories"][0]["duration_ms"] == 1_800_000
            request("POST", "/v1/checkins", {"id": "smoke-checkin", "at_ms": now - 1000, "mood": 3, "energy": 4})
            request("POST", "/v1/reflections", {"id": "smoke-reflection", "at_ms": now - 1000, "text": "Disposable reflection"})
            run("docker", "run", "-d", "--name", worker, "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "192m", "--entrypoint", "pocketlog-worker", "-v", volume + ":/data", "-v", config_volume + ":/config:ro", "-e", "POCKETLOG_AI_CONFIG_DIR=/config", image)
            path = f"/v1/timeline?from_ms={now - 7_200_000}&to_ms={now}"
            for attempt in range(30):
                data = request("GET", path)
                if data["cards"][0]["source"] == "local_excerpt":
                    break
                time.sleep(0.2)
            else:
                raise AssertionError("worker did not process context")
            assert len(data["checkins"]) == len(data["reflections"]) == 1
            assert request("GET", "/v1/status")["pending_context"] == 0
            request("POST", "/v1/observations", observation)
            assert request("GET", "/v1/status")["pending_context"] == 0, "replay resurrected raw content"
            request("POST", "/v1/cards", {"id": activity["id"], "title": "My correction", "summary": "Disposable note", "category": "rest"})
            assert request("GET", path)["cards"][0]["source"] == "user"
            # The documented example client reads the journal through the paged contract.
            sys.dont_write_bytecode = True  # Keep docs/examples free of __pycache__.
            spec = importlib.util.spec_from_file_location("pocketlog_client", Path(__file__).resolve().parent.parent / "docs" / "examples" / "pocketlog_client.py")
            example = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(example)
            client = example.Client(origin, token, allow_loopback_http=True)
            assert {"activity_pages", "activity_search", "json_errors"} <= client.capabilities()
            cards = list(client.read_activities(now - 7_200_000, now, limit=1))
            assert [c["id"] for c in cards] == ["smoke-session"] and cards[0]["source"] == "user"
            assert [c["id"] for c in client.read_activities(now - 7_200_000, now, q="CORRECTION")] == ["smoke-session"]
            assert list(client.read_activities(now - 7_200_000, now, category="work")) == []
            client.correct(activity["id"], "rest", "My correction", "Disposable note")
            try:
                client.request("GET", "/v1/activities", query={"from_ms": 1, "to_ms": 0})
                raise AssertionError("invalid window accepted")
            except example.ApiError as failure:
                assert (failure.status, failure.error) == (400, "invalid request")
            # Restart validates persistence and additive schema setup with the same volume.
            run("docker", "restart", server)
            origin = "http://127.0.0.1:" + run("docker", "port", server, "8080/tcp").rsplit(":", 1)[1]
            for attempt in range(30):
                try:
                    assert len(request("GET", path)["checkins"]) == 1
                    break
                except (OSError, urllib.error.URLError):
                    time.sleep(0.2)
            else:
                raise AssertionError("server did not recover after restart")
            request("DELETE", "/v1/journal")
            data = request("GET", path)
            assert all(data[key] == [] for key in ("activities", "cards", "checkins", "reflections"))
            assert request("GET", "/v1/status")["pending_context"] == 0
            logs = run("docker", "logs", server) + run("docker", "logs", worker)
            for private in (token, observation["text"], "Disposable reflection", "My correction"):
                assert private not in logs, "private input entered container logs"
            print("Disposable Docker smoke passed: auth, persistence, API + worker, replay, correction, deletion, private logs.")
        finally:
            for name in (worker, server):
                subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            subprocess.run(["docker", "volume", "rm", volume, config_volume], stdout=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    main()
