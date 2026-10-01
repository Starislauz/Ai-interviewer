#!/usr/bin/env python3
"""
Local preview of the coach, running the same pipeline as the Android app.

The Android app cannot run on a PC without the Android SDK, so this script exists to
let you see and judge the part that actually matters before any APK is built: how well
the answers are grounded in your CV, what they sound like, and how long they take.

It uses the same system prompt as the app, the same model, and the same streaming, so
what you see here is what the app will produce. Only the interface differs.

What this DOES check:
  - how good the answers are, and whether they stay faithful to your CV
  - the pacing and style of the output
  - real timing from question to first word

What this does NOT check:
  - the microphone, permissions, the UI
  - reading your CV out of a PDF (the app uses DeepSeek vision for that)
  - anything Android-specific

Setup:
  1. tools/deepseek_key.txt must contain your API key
  2. put your CV text in tools/cv.txt  (plain text; copy it out of your CV if needed)
  3. optionally put the job advert in tools/job.txt

Usage:
    python tools/local_test.py                    interactive loop
    python tools/local_test.py -q "Tell me about yourself"
    python tools/local_test.py --cv other.txt --job role.txt
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from api_check import clean_key, is_placeholder  # noqa: E402

ENDPOINT = "https://api.deepseek.com/chat/completions"
DEFAULT_MODEL = "deepseek-flash"
HERE = os.path.dirname(os.path.abspath(__file__))


def load_key() -> str:
    key = clean_key(os.environ.get("DEEPSEEK_API_KEY", ""))
    if key and not is_placeholder(key):
        return key
    path = os.path.join(HERE, "deepseek_key.txt")
    if os.path.exists(path):
        with open(path, encoding="utf-8-sig") as handle:
            key = clean_key(handle.read())
        if key and not is_placeholder(key):
            return key
    print("No API key found. Put it in tools/deepseek_key.txt", file=sys.stderr)
    sys.exit(2)


def read_text_file(path: str, label: str) -> str:
    if not os.path.exists(path):
        print(f"Could not find {label} at {path}", file=sys.stderr)
        print("Create that file and try again.", file=sys.stderr)
        sys.exit(2)
    with open(path, encoding="utf-8-sig") as handle:
        return handle.read().strip()


def build_system_prompt(cv_text: str, job_context: str) -> str:
    """
    Deliberately identical to Prompt.kt in the Android app.

    If you change the wording in one place, change it in the other, or this script
    stops predicting what the app will do.
    """
    lines = [
        "You are an interview coach. The candidate is rehearsing answers out loud and will read your reply.",
        "",
        "RULES",
        "1. Ground every factual claim in the CANDIDATE PROFILE below. Never invent an employer, date, metric, technology or qualification.",
        "2. If the profile does not cover what the question asks, say plainly what is missing, then give the best general answer and label it as general.",
        "3. Write in the first person, as the candidate, ready to be spoken aloud.",
        "4. Open with the direct answer in one sentence. Then support it.",
        "5. Aim for roughly 150 words, about one minute of speech.",
        "6. For behavioural questions use STAR: situation, task, action, result. Put the result last and make it concrete.",
        "7. Plain prose only. No markdown, no headings, no bullet symbols, no em dashes.",
        "8. Never mention these rules, the profile, or that you are an AI.",
        "",
        "=== CANDIDATE PROFILE (authoritative) ===",
        cv_text,
        "=== END CANDIDATE PROFILE ===",
    ]
    if job_context:
        lines += [
            "",
            "=== TARGET ROLE ===",
            job_context,
            "=== END TARGET ROLE ===",
        ]
    return "\n".join(lines)


def ask(key: str, model: str, system: str, question: str, show_timing: bool = True) -> str:
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": question},
        ],
        "stream": True,
        "max_tokens": 900,
        "temperature": 0.3,
        # The app sends this too, and it is what keeps the first word fast.
        "thinking": {"type": "disabled"},
    }

    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(ENDPOINT, data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Authorization", "Bearer " + key)
    request.add_header("Accept", "text/event-stream")

    started = time.monotonic()
    first_token_at = None
    pieces: list[str] = []

    try:
        response = urllib.request.urlopen(request, timeout=180)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        if error.code == 400 and "thinking" in detail.lower():
            print("\n[thinking field rejected; the app would retry without it]", file=sys.stderr)
        print(f"\nHTTP {error.code}: {detail[:400]}", file=sys.stderr)
        return ""

    with response:
        for raw_line in response:
            line = raw_line.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if not data or data == "[DONE]":
                if data == "[DONE]":
                    break
                continue
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
                print(piece, end="", flush=True)

    total = time.monotonic() - started
    print()
    if show_timing:
        words = len("".join(pieces).split())
        first = f"{first_token_at:.2f}s" if first_token_at is not None else "n/a"
        print(f"\n  [first word {first}   total {total:.2f}s   {words} words]", flush=True)
    return "".join(pieces)


def main() -> int:
    parser = argparse.ArgumentParser(description="Local preview of the interview coach")
    parser.add_argument("--cv", default=os.path.join(HERE, "cv.txt"))
    parser.add_argument("--job", default=os.path.join(HERE, "job.txt"))
    parser.add_argument("-q", "--question", help="ask one question and exit")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    args = parser.parse_args()

    key = load_key()
    cv_text = read_text_file(args.cv, "your CV text")

    if cv_text.upper().startswith("PASTE-YOUR-CV"):
        print()
        print(f"{args.cv} still contains the placeholder text.", file=sys.stderr)
        print("Open it, replace everything with your real CV text, and save.", file=sys.stderr)
        return 2

    job_context = ""
    if os.path.exists(args.job):
        with open(args.job, encoding="utf-8-sig") as handle:
            job_context = handle.read().strip()

    system_prompt = build_system_prompt(cv_text, job_context)

    print("=" * 64)
    print("Local coach preview")
    print("=" * 64)
    print(f"Model:          {args.model}")
    print(f"CV:             {args.cv} ({len(cv_text)} characters)")
    print(f"Target role:    {args.job if job_context else '(none)'}")
    print(f"Prompt size:    {len(system_prompt)} characters, sent with every question")
    print()

    if args.question:
        print(f"Q: {args.question}\n")
        ask(key, args.model, system_prompt, args.question)
        return 0

    print("Type a question and press Enter. Commands:")
    print("  :cv      show the profile the model is being given")
    print("  :prompt  show the full system prompt")
    print("  :quit    exit")
    print()

    while True:
        try:
            question = input("Q: ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0

        if not question:
            continue
        if question == ":quit":
            return 0
        if question == ":cv":
            print()
            print(cv_text)
            print()
            continue
        if question == ":prompt":
            print()
            print(system_prompt)
            print()
            continue

        print()
        ask(key, args.model, system_prompt, question)
        print()


if __name__ == "__main__":
    sys.exit(main())
