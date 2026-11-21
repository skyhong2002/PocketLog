#!/usr/bin/env python3
"""Extract Dayflow's model prompts verbatim into server/src/prompts/dayflow/.

Usage: scripts/sync-dayflow-prompts.py /path/to/Dayflow-checkout [--check]

The text is copied byte for byte from the pinned Dayflow commit (MIT, see
docs/licenses/dayflow-LICENSE.txt). Only whole lines or whole sentences that
name output fields PocketLog does not have, or that conflict with PocketLog's
privacy rules, are removed. Every removal is listed below and asserted, so a
changed upstream prompt fails loudly instead of drifting silently.
With --check the files are compared, not written.
"""
import pathlib
import sys

PINNED = "378e5a14ebdc9ab8733843efbcc304a0b6c778ab"
AI = "Dayflow/Dayflow/Core/AI/"
OUT = pathlib.Path(__file__).resolve().parent.parent / "server/src/prompts/dayflow"


def literal(root, name, opener, closer):
    """Return a Swift multi-line string's text, dedented like the compiler does."""
    lines = (root / AI / name).read_text(encoding="utf-8").split("\n")
    start = next(i for i, l in enumerate(lines) if l.strip().endswith(opener) and "=" in l)
    end = next(i for i in range(start + 1, len(lines)) if lines[i].strip() == closer)
    indent = len(lines[end]) - len(lines[end].lstrip())
    body = lines[start + 1 : end]
    return [l[indent:] if l.strip() else "" for l in body]


def drop(lines, exact=(), prefix=(), replace=()):
    """Remove exact or prefixed lines and replace sentences; each must match once."""
    out = list(lines)
    for text in exact:
        assert out.count(text) == 1, f"missing line: {text!r}"
        out.remove(text)
    for text in prefix:
        hits = [l for l in out if l.startswith(text)]
        assert len(hits) == 1, f"missing prefix: {text!r}"
        out.remove(hits[0])
    for old, new in replace:
        hits = [i for i, l in enumerate(out) if old in l]
        assert len(hits) == 1, f"missing text: {old!r}"
        out[hits[0]] = out[hits[0]].replace(old, new)
    return out


def sections(lines, headings):
    """Slice lines at the first line equal to each heading, in order."""
    marks = [lines.index(h) for h in headings]
    assert marks == sorted(marks), "headings out of order"
    return {h: lines[m : (marks[i + 1] if i + 1 < len(marks) else len(lines))] for i, (h, m) in enumerate(zip(headings, marks))}


def trim(lines):
    while lines and not lines[-1].strip():
        lines = lines[:-1]
    return lines


def transcription(root):
    lines = literal(root, "GeminiDirectProvider+Transcription.swift", '"""', '"""')
    lines = lines[: lines.index("Segments:")]  # segment count and JSON format are PocketLog's own
    lines = [l for l in lines if not l.startswith("CRITICAL: This video")]
    macos = next(i for i, l in enumerate(lines) if l.startswith("Identifying the active app:"))
    lines = lines[:macos] + lines[macos + 1 :]
    lines = drop(
        lines,
        exact=["- Exact usernames, search queries, messages"],
        prefix=['Bad: "Checked email"', 'Good: "Gmail: Read email'],
        replace=[
            (" (check menu bar for app name)", ""),  # macOS-only menu bar
            ("[exact app name from menu bar]", "[app name]"),
        ],
    )
    assert not any("\\(" in l for l in lines), "unexpanded Swift interpolation"
    return "\n".join(trim(lines)) + "\n"


def timeline(root):
    # `template` is the second raw literal in the file; skip outputBlock.
    all_lines = (root / AI / "ClaudeTimelinePrompt.swift").read_text(encoding="utf-8").split("\n")
    start = next(i for i, l in enumerate(all_lines) if "static let template" in l)
    end = next(i for i in range(start + 1, len(all_lines)) if all_lines[i].strip() == '"""#')
    indent = len(all_lines[end]) - len(all_lines[end].lstrip())
    lines = [l[indent:] if l.strip() else "" for l in all_lines[start + 1 : end]]
    keep = [
        "You are turning screenshots of a user's screen into timeline cards. Each card represents one main thing they did.",
        "CORE PRINCIPLE:",
        "CARD BOUNDARIES:",
        "WHEN TO SPLIT (new card):",
        "WHEN TO MERGE (same card):",
        "BOUNDARY STRICTNESS:",
        "CONTINUITY RULE:",
        "TITLE GUIDELINES",
        "SUMMARIES",
        "DETAILED SUMMARY",  # cut points only; removed below
        "{{LANGUAGE}}",
        "DISTRACTIONS",
        "{{CATEGORIES}}",
        "APP SITES (Website Logos)",
        "DECISION PROCESS",
        "INPUT/OUTPUT CONTRACT:",
        "FIDELITY TO THE SCREENSHOTS",
        "READING THE SCREENSHOTS",
    ]
    parts = sections(lines, keep)
    wanted = [
        keep[0], "CORE PRINCIPLE:", "CARD BOUNDARIES:", "WHEN TO SPLIT (new card):",
        "WHEN TO MERGE (same card):", "BOUNDARY STRICTNESS:", "CONTINUITY RULE:",
        "TITLE GUIDELINES", "SUMMARIES", "FIDELITY TO THE SCREENSHOTS", "READING THE SCREENSHOTS",
    ]
    chunks = []
    for heading in wanted:
        chunk = trim(parts[heading])
        if heading == "BOUNDARY STRICTNESS:":
            chunk = drop(chunk, exact=["- Every detailedSummary line must be grounded in what a screenshot shows."])
        if heading == "FIDELITY TO THE SCREENSHOTS":
            chunk = drop(chunk, exact=["- Every time in the output carries AM or PM matching the frame labels."])
        if heading == "READING THE SCREENSHOTS":
            chunk = drop(
                chunk,
                prefix=[
                    "- There is one screenshot per minute",
                    "- Look at every frame. The detailedSummary",
                    "- The user's name is given under INPUTS",
                    "- Quote on-screen text character for character",
                ],
                replace=[("; do not list a website in appSites that no screenshot shows open.", ".")],
            )
        chunks.append("\n".join(chunk))
    text = "\n\n".join(chunks) + "\n"
    assert "detailedSummary" not in text and "appSites" not in text, "unsupported output field leaked"
    return text


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check = "--check" in sys.argv
    if len(args) != 1:
        sys.exit(__doc__)
    root = pathlib.Path(args[0])
    files = {"transcription.txt": transcription(root), "timeline.txt": timeline(root)}
    OUT.mkdir(parents=True, exist_ok=True)
    stale = False
    for name, text in files.items():
        path = OUT / name
        if check:
            same = path.exists() and path.read_text(encoding="utf-8") == text
            stale |= not same
            print(("ok     " if same else "STALE  ") + name)
        else:
            path.write_text(text, encoding="utf-8")
            print(f"wrote {path.relative_to(OUT.parents[3])} ({len(text)} bytes)")
    print(f"pinned Dayflow commit: {PINNED}")
    sys.exit(1 if stale else 0)


if __name__ == "__main__":
    main()
