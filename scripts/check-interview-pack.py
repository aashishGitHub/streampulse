#!/usr/bin/env python3
"""Checks docs/interview/ against the repo it describes (specs/14-interview-packaging).

1. Every repo path named in backticks, and every relative markdown link, must exist.
2. Every row of the numbers table in 06-numbers.md must say measured, target or assumption;
   a measured row's source must be `MILESTONES: <text>` where <text> appears in a MILESTONES.md heading.

   A path written as `MISSING:docs/x.md` asserts the file is ABSENT; it fails if the file appears.
3. Every file under phases/ must contain Beginner, Mid and Senior questions, as headings
   `### B1.`, `### M1.`, `### S1.`.

Paths outside the repo (../Java/...) are reported as warnings: they exist on one machine only.
Standard library only. Exit 0 when clean, 1 otherwise.
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK = os.path.join(ROOT, "docs", "interview")
PHASES = [
    "phase-00-foundation", "phase-01-contracts", "phase-02-ingest-gateway", "phase-03-stream-processing",
    "phase-04-sink-and-storage", "phase-05-query-api", "phase-06-control-plane-cdc-alerts",
    "phase-07-react-dashboard", "phase-08-observability", "phase-09-resilience-and-load",
    "phase-10-aws-and-terraform", "phase-11-containers", "phase-12-activity-feed", "phase-13-mcp-server",
]
REQUIRED = [
    "README.md", "01-the-story.md", "05-what-broke.md", "06-numbers.md", "07-question-map.md",
    "08-mock-interviews.md",
] + ["phases/%s.md" % p for p in PHASES]
LEVELS = ("Beginner", "Mid", "Senior")
TOP_DIRS = ("services/", "libs/", "tools/", "docs/", "specs/", "infra/", "docker/", "scripts/",
            "functions/", "web/", "api/", "deploy/", "build-logic/", "gradle/", ".github/", "../")
TOP_FILES = ("MILESTONES.md", "README.md", "Makefile", "CLAUDE.md", "RESUME-HERE.md",
             "settings.gradle.kts", ".mcp.json")
KINDS = ("measured", "target", "assumption")

BACKTICK = re.compile(r"`([^`\s]+)`")
MD_LINK = re.compile(r"\]\(([^)\s]+)\)")


def looks_like_path(token: str) -> bool:
    return token.startswith(TOP_DIRS) or token in TOP_FILES


def normalise(token: str) -> str:
    token = token.split("#", 1)[0]
    token = re.sub(r":\d+(-\d+)?$", "", token)
    return token.rstrip(".,;)")


def pack_files():
    for folder, _dirs, names in os.walk(PACK):
        for name in sorted(names):
            if name.endswith(".md"):
                yield os.path.join(folder, name)


def check_levels(errors):
    for path in pack_files():
        rel = os.path.relpath(path, PACK)
        if not rel.startswith("phases" + os.sep):
            continue
        with open(path, encoding="utf-8") as f:
            text = f.read()
        for level in LEVELS:
            count = len(re.findall(r"^### %s\d+\." % level[0], text, re.M))
            if count == 0:
                errors.append("%s: no %s questions (expected headings like '### %s1.')" % (rel, level, level[0]))


def check_paths(errors, warnings):
    checked = 0
    for path in pack_files():
        name = os.path.relpath(path, PACK)
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
        in_fence = False
        for number, line in enumerate(lines, 1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for token in BACKTICK.findall(line):
                if token.startswith("MISSING:"):
                    absent = normalise(token[len("MISSING:"):])
                    checked += 1
                    if os.path.exists(os.path.join(ROOT, absent)):
                        errors.append("%s:%d: claimed missing but it now exists: %s" % (name, number, absent))
                    continue
                if not looks_like_path(token) or any(c in token for c in "*<>{}$"):
                    continue
                target = normalise(token)
                checked += 1
                if not os.path.exists(os.path.join(ROOT, target)):
                    (warnings if target.startswith("../") else errors).append(
                        "%s:%d: path does not exist: %s" % (name, number, target))
            for link in MD_LINK.findall(line):
                if link.startswith(("http://", "https://", "#", "mailto:")):
                    continue
                target = normalise(link)
                checked += 1
                resolved = os.path.normpath(os.path.join(os.path.dirname(path), target))
                if not os.path.exists(resolved):
                    (warnings if not resolved.startswith(ROOT) else errors).append(
                        "%s:%d: link does not resolve: %s" % (name, number, link))
    return checked


def check_numbers(errors):
    with open(os.path.join(ROOT, "MILESTONES.md"), encoding="utf-8") as f:
        headings = [l for l in f.read().splitlines() if l.startswith("#")]
    rows = 0
    with open(os.path.join(PACK, "06-numbers.md"), encoding="utf-8") as f:
        lines = f.read().splitlines()
    for number, line in enumerate(lines, 1):
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not line.startswith("|") or len(cells) < 4 or set(cells[0]) <= set("-: "):
            continue
        if cells[0].lower() in ("claim", "number", "what"):
            continue
        rows += 1
        kind = cells[2].lower()
        source = cells[3]
        if kind not in KINDS:
            errors.append("06-numbers.md:%d: kind must be one of %s, got '%s'" % (number, KINDS, cells[2]))
            continue
        if kind == "measured":
            m = re.search(r"MILESTONES:\s*(.+)", source)
            fragment = m.group(1).strip().strip("`") if m else ""
            if not fragment or not any(fragment in h for h in headings):
                errors.append("06-numbers.md:%d: measured value needs `MILESTONES: <heading text>` "
                              "that matches a MILESTONES.md heading; got '%s'" % (number, source))
        elif not source:
            errors.append("06-numbers.md:%d: %s value has no source" % (number, kind))
    if rows == 0:
        errors.append("06-numbers.md: no number rows found")
    return rows


def main() -> int:
    if not os.path.isdir(PACK):
        print("FAIL: %s does not exist" % os.path.relpath(PACK, ROOT))
        return 1
    errors, warnings = [], []
    missing = [n for n in REQUIRED if not os.path.exists(os.path.join(PACK, n))]
    for n in missing:
        errors.append("missing required file: docs/interview/%s" % n)
    paths = check_paths(errors, warnings)
    check_levels(errors)
    rows = check_numbers(errors) if "06-numbers.md" not in missing else 0
    for w in warnings:
        print("warning: " + w)
    for e in errors:
        print("error: " + e)
    print("checked %d path references, %d number rows: %s"
          % (paths, rows, "PASS" if not errors else "FAIL (%d error(s))" % len(errors)))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
