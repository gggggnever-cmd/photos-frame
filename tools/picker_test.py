#!/usr/bin/env python3
"""Throwaway spike: does the Google Photos Picker work well enough for a TV frame app?

What it checks:
  * how picking feels (can you grab a whole album, or must you tap each photo?)
  * how many items one session returns
  * that we can download TV-sized (3840x2160) copies

Setup (one time):
  1. Google Cloud Console -> new project -> enable "Photos Picker API".
  2. OAuth consent screen: External, add yourself as a test user.
  3. Credentials -> Create OAuth client ID -> "Desktop app" -> download JSON.
  4. Save it next to this script as client_secret.json.

Run on your own computer (sign-in opens your local browser):
  python3 picker_test.py

Standard library only. Nothing is stored except the downloaded samples in ./samples/.
"""
import base64
import hashlib
import http.server
import json
import os
import secrets
import sys
import threading
import time
import urllib.parse
import urllib.request
import webbrowser

SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
API = "https://photospicker.googleapis.com/v1"
HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = 3


def load_client():
    path = os.path.join(HERE, "client_secret.json")
    if not os.path.exists(path):
        sys.exit("Missing client_secret.json next to this script (see setup notes at top).")
    with open(path) as f:
        data = json.load(f)
    return data.get("installed") or data.get("web")


def get_token(client):
    """Loopback OAuth with PKCE: opens the browser, catches the redirect on localhost."""
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    result = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if "code" in q or "error" in q:
                result.update({k: v[0] for k, v in q.items()})
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"Signed in. You can close this tab and go back to the terminal.")
            else:
                self.send_response(404)
                self.end_headers()

        def log_message(self, *a):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    redirect = f"http://127.0.0.1:{server.server_port}"
    threading.Thread(target=server.serve_forever, daemon=True).start()

    url = client["auth_uri"] + "?" + urllib.parse.urlencode({
        "client_id": client["client_id"],
        "redirect_uri": redirect,
        "response_type": "code",
        "scope": SCOPE,
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    })
    print("Opening browser for Google sign-in...\nIf it doesn't open, visit:\n" + url + "\n")
    webbrowser.open(url)
    while not result:
        time.sleep(0.3)
    server.shutdown()

    if "error" in result:
        sys.exit("Sign-in failed: " + result["error"])
    if result.get("state") != state:
        sys.exit("State mismatch; aborting.")

    body = urllib.parse.urlencode({
        "code": result["code"],
        "client_id": client["client_id"],
        "client_secret": client.get("client_secret", ""),
        "redirect_uri": redirect,
        "grant_type": "authorization_code",
        "code_verifier": verifier,
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(client["token_uri"], data=body)) as r:
        return json.load(r)["access_token"]


def call(token, method, path, body=None):
    req = urllib.request.Request(
        API + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as r:
        raw = r.read()
        return json.loads(raw) if raw else {}


def seconds(duration):  # API durations look like "5s" or "1800.5s"
    return float(str(duration).rstrip("s") or 0)


def main():
    client = load_client()
    token = get_token(client)

    session = call(token, "POST", "/sessions", {})
    sid = session["id"]
    print("Open this link to pick photos (phone or computer, same Google account):")
    print("  " + session["pickerUri"] + "\n")
    print("Try: open the Albums tab and see whether you can select a whole album at once.")
    print("Tap Done in the picker when finished. Waiting...\n")
    webbrowser.open(session["pickerUri"])

    started = time.time()
    while True:
        s = call(token, "GET", f"/sessions/{sid}")
        if s.get("mediaItemsSet"):
            break
        cfg = s.get("pollingConfig", {})
        if time.time() - started > seconds(cfg.get("timeoutIn", "1800s")) + 5:
            sys.exit("Picker session timed out.")
        time.sleep(max(seconds(cfg.get("pollInterval", "5s")), 2))
    print(f"Picking took {time.time() - started:.0f}s.")

    items, page = [], None
    while True:
        q = {"sessionId": sid, "pageSize": 100}
        if page:
            q["pageToken"] = page
        r = call(token, "GET", "/mediaItems?" + urllib.parse.urlencode(q))
        items += r.get("mediaItems", [])
        page = r.get("nextPageToken")
        if not page:
            break

    photos = [i for i in items if i.get("type") == "PHOTO"]
    print(f"Got {len(items)} items ({len(photos)} photos, {len(items) - len(photos)} other).")

    os.makedirs(os.path.join(HERE, "samples"), exist_ok=True)
    for n, item in enumerate(photos[:SAMPLES], 1):
        mf = item["mediaFile"]
        url = mf["baseUrl"] + "=w3840-h2160"  # fit within 4K, keeps aspect ratio
        req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
        out = os.path.join(HERE, "samples", f"{n}_{mf.get('filename', item['id'] + '.jpg')}")
        with urllib.request.urlopen(req) as r, open(out, "wb") as f:
            f.write(r.read())
        print(f"  saved {out} ({os.path.getsize(out) // 1024} KB)")

    call(token, "DELETE", f"/sessions/{sid}")
    print("\nDone. Things to note for the design:")
    print("  - Could you select a whole album in one go, or only individual photos?")
    print("  - How long did picking take, and would you do it again every few weeks?")
    print("  - Do the samples look sharp enough at 85 inches?")


# ---- Cloud mode: same flow split into steps, for when the browser is on another machine ----
STATE = os.path.join(HERE, ".picker_state.json")
FIXED_REDIRECT = "http://127.0.0.1:8765"


def save(d):
    with open(STATE, "w") as f:
        json.dump(d, f)
    os.chmod(STATE, 0o600)


def load():
    with open(STATE) as f:
        return json.load(f)


def step_auth_url(client):
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    save({"verifier": verifier, "state": state})
    print(client["auth_uri"] + "?" + urllib.parse.urlencode({
        "client_id": client["client_id"], "redirect_uri": FIXED_REDIRECT, "response_type": "code",
        "scope": SCOPE, "state": state, "code_challenge": challenge, "code_challenge_method": "S256",
    }))


def step_code(client, pasted):
    st = load()
    q = {k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlparse(pasted).query).items()}
    if "error" in q:
        sys.exit("Sign-in failed: " + q["error"])
    if q.get("state") != st["state"]:
        sys.exit("State mismatch; start again with 'auth'.")
    body = urllib.parse.urlencode({
        "code": q["code"], "client_id": client["client_id"], "client_secret": client.get("client_secret", ""),
        "redirect_uri": FIXED_REDIRECT, "grant_type": "authorization_code", "code_verifier": st["verifier"],
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(client["token_uri"], data=body)) as r:
        st["token"] = json.load(r)["access_token"]
    session = call(st["token"], "POST", "/sessions", {})
    st["sid"], st["started"] = session["id"], time.time()
    save(st)
    print(session["pickerUri"])


def step_fetch():
    st = load()
    token, sid = st["token"], st["sid"]
    s = call(token, "GET", f"/sessions/{sid}")
    if not s.get("mediaItemsSet"):
        print("NOT_DONE")
        return
    items, page = [], None
    while True:
        q = {"sessionId": sid, "pageSize": 100}
        if page:
            q["pageToken"] = page
        r = call(token, "GET", "/mediaItems?" + urllib.parse.urlencode(q))
        items += r.get("mediaItems", [])
        page = r.get("nextPageToken")
        if not page:
            break
    photos = [i for i in items if i.get("type") == "PHOTO"]
    print(f"Got {len(items)} items ({len(photos)} photos, {len(items) - len(photos)} other).")
    with open(os.path.join(HERE, "picked_items.json"), "w") as f:
        json.dump([{"type": i.get("type"), "createTime": i.get("createTime"),
                    "filename": i.get("mediaFile", {}).get("filename"),
                    "mimeType": i.get("mediaFile", {}).get("mimeType")} for i in items], f, indent=1)
    os.makedirs(os.path.join(HERE, "samples"), exist_ok=True)
    for n, item in enumerate(photos[:SAMPLES], 1):
        mf = item["mediaFile"]
        req = urllib.request.Request(mf["baseUrl"] + "=w3840-h2160", headers={"Authorization": "Bearer " + token})
        out = os.path.join(HERE, "samples", f"{n}_{mf.get('filename', item['id'] + '.jpg')}")
        with urllib.request.urlopen(req) as r, open(out, "wb") as f:
            f.write(r.read())
        print(f"  saved {out} ({os.path.getsize(out) // 1024} KB)")
    call(token, "DELETE", f"/sessions/{sid}")
    os.remove(STATE)  # drop the token


if __name__ == "__main__":
    # Local:  python3 picker_test.py
    # Cloud:  python3 picker_test.py auth | code '<pasted URL>' | fetch
    if len(sys.argv) > 1:
        c = load_client()
        {"auth": lambda: step_auth_url(c), "code": lambda: step_code(c, sys.argv[2]), "fetch": step_fetch}[sys.argv[1]]()
    else:
        main()
