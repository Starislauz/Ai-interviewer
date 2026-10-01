#!/usr/bin/env python3
"""
Pre-flight check for the DeepSeek settings this app depends on.

Run this BEFORE installing Android Studio. It verifies, in about two minutes, the
four things that would otherwise only show up as confusing errors inside the app:

  1. the API key works at all
  2. the model name "deepseek-flash" is accepted
  3. `thinking: disabled` is accepted (the whole speed story depends on it)
  4. how long the first token actually takes, so the app's latency is not a surprise

It uses only the Python standard library, so there is nothing to pip install.

Put your key in ONE of these places:
  - an environment variable named DEEPSEEK_API_KEY, or
  - a file next to this script called deepseek_key.txt containing just the key

The key is never printed. Only its length is, which is enough to spot a bad paste.

Usage:
    python tools/api_check.py
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

ENDPOINT = "https://api.deepseek.com/chat/completions"
MODEL = "deepseek-flash"

SYSTEM_PROMPT = "You are an interview coach. Answer in one short paragraph."
USER_PROMPT = "What makes a good answer to 'tell me about yourself'?"


def is_placeholder(value: str) -> bool:
    """True while the key file still holds the instruction text."""
    return value.upper().startswith("PASTE")


def clean_key(value: str) -> str:
    """
    Removes the things that get pasted in by accident: a byte-order mark from
    Notepad, surrounding whitespace, and quote characters. Any of these would
    otherwise end up inside the key and come back as a confusing 401.
    """
    return value.lstrip("\ufeff").strip().strip('"').strip("'").strip()


def load_key() -> str:
    key = clean_key(os.environ.get("DEEPSEEK_API_KEY", ""))
    if key and not is_placeholder(key):
        print("Key source: environment variable DEEPSEEK_API_KEY")
        return key

    here = os.path.dirname(os.path.abspath(__file__))
    key_file = os.path.join(here, "deepseek_key.txt")
    if os.path.exists(key_file):
        # utf-8-sig strips a byte-order mark, which Notepad sometimes adds.
        with open(key_file, encoding="utf-8-sig") as handle:
            key = clean_key(handle.read())
        if key and not is_placeholder(key):
            print(f"Key source: {key_file}")
            return key
        if is_placeholder(key):
            print(f"The file at {key_file}", file=sys.stderr)
            print("still contains the placeholder text.", file=sys.stderr)
            print("Open it and replace the whole line with your key.", file=sys.stderr)
            sys.exit(2)
        print(f"The file at {key_file} is empty.", file=sys.stderr)
        print("Open it and paste your key in.", file=sys.stderr)
        sys.exit(2)

    print("No API key found.", file=sys.stderr)
    print("", file=sys.stderr)
    print("Either set an environment variable:", file=sys.stderr)
    print('  $env:DEEPSEEK_API_KEY = "sk-..."', file=sys.stderr)
    print("or create tools/deepseek_key.txt containing just the key.", file=sys.stderr)
    sys.exit(2)


def send(key: str, payload: dict, timeout: int = 180):
    """POST the payload. Returns the open response, or raises HTTPError."""
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(ENDPOINT, data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Authorization", "Bearer " + key)
    request.add_header("Accept", "text/event-stream")
    return urllib.request.urlopen(request, timeout=timeout)


def read_error(error: urllib.error.HTTPError) -> str:
    try:
        return error.read().decode("utf-8", "replace")
    except Exception:
        return ""


def build_payload(stream: bool, disable_thinking: bool) -> dict:
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": USER_PROMPT},
    ]
    payload = {
        "model": MODEL,
        "messages": messages,
        "stream": stream,
        "max_tokens": 600,
        "temperature": 0.3,
    }
    if disable_thinking:
        payload["thinking"] = {"type": "disabled"}
    return payload


def test_non_streaming(key: str) -> bool:
    """Confirms the key, the model name, and the thinking switch."""
    print()
    print("Test 1 - plain request, thinking disabled")
    print("-" * 46)

    started = time.monotonic()
    try:
        with send(key, build_payload(stream=False, disable_thinking=True)) as response:
            code = response.status
            body = response.read().decode("utf-8", "replace")
        elapsed = time.monotonic() - started

        choices = json.loads(body).get("choices") or [{}]
        content = (choices[0].get("message") or {}).get("content") or ""
        print(f"  HTTP {code} in {elapsed:.2f}s")
        print(f"  Reply: {content[:160].strip()}")
        print("  PASS - key, model and thinking switch all accepted.")
        return True

    except urllib.error.HTTPError as error:
        body = read_error(error)
        elapsed = time.monotonic() - started
        print(f"  HTTP {error.code} in {elapsed:.2f}s")
        print(f"  Body: {body[:400]}")

        if "thinking" in body.lower():
            print("  The thinking switch was rejected. The app already falls back")
            print("  automatically, so this is survivable - but tell me and I will")
            print("  remove the field rather than rely on the retry.")
            return True

        if error.code == 401:
            print("  That key was not accepted. Check for a stray space or a")
            print("  truncated paste.")
        elif error.code == 402:
            print("  The account has no credit. Top up at platform.deepseek.com.")
        elif error.code == 404:
            print(f"  The model name {MODEL!r} may have changed. Check the pricing page.")
        return False

    except Exception as error:
        print(f"  {type(error).__name__}: {error}")
        print("  Could not reach the API at all. Check the network.")
        return False


def test_streaming(key: str) -> bool:
    """Measures time to first token, which is what the app's speed depends on."""
    print()
    print("Test 2 - streaming, time to first token")
    print("-" * 46)

    started = time.monotonic()
    first_token_at = None
    pieces: list[str] = []

    try:
        response = send(key, build_payload(stream=True, disable_thinking=True))
    except urllib.error.HTTPError as error:
        print(f"  HTTP {error.code}: {read_error(error)[:300]}")
        return False
    except Exception as error:
        print(f"  {type(error).__name__}: {error}")
        return False

    try:
        with response:
            for raw_line in response:
                line = raw_line.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if not data:
                    continue
                if data == "[DONE]":
                    break
                try:
                    parsed = json.loads(data)
                except json.JSONDecodeError:
                    continue
                choices = parsed.get("choices") or [{}]
                delta = choices[0].get("delta") or {}
                piece = delta.get("content")
                if isinstance(piece, str) and piece:
                    if first_token_at is None:
                        first_token_at = time.monotonic() - started
                    pieces.append(piece)
    except Exception as error:
        print(f"  Stream broke: {type(error).__name__}: {error}")
        return False

    total = time.monotonic() - started
    if first_token_at is None:
        print("  No text arrived. The stream opened but produced nothing.")
        return False

    print(f"  First token: {first_token_at:.2f}s   (app claims 0.3 - 0.8s)")
    print(f"  Full answer: {total:.2f}s   ({len(''.join(pieces))} characters)")
    print()
    if first_token_at <= 1.2:
        print("  PASS - fast enough that pressing Answer will feel instant.")
    else:
        print("  PASS, but slower than expected. This is usually network latency")
        print("  between you and the API rather than the model itself.")
    return True


def main() -> int:
    print("DeepSeek pre-flight check")
    print("=" * 46)

    key = load_key()
    print(f"Key length: {len(key)} characters")
    print(f"Model:      {MODEL}")

    first = test_non_streaming(key)
    if not first:
        print()
        print("Stopping here - fix the problem above before testing streaming.")
        return 1

    second = test_streaming(key)

    print()
    print("=" * 46)
    if first and second:
        print("Both checks passed. The app's API half is sound.")
        print("Next: push the project to a private GitHub repo and run the")
        print("'Build APK' workflow from the Actions tab.")
        return 0
    print("Something failed. Send me the output above.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
