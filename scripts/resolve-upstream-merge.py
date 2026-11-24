#!/usr/bin/env python3
"""Finish the merge of an upstream HushGram release into the fork's main.

Run it from the repository root right after `git merge --no-commit --no-ff <tag>`, whether or not the
merge stopped on conflicts:

    python3 scripts/resolve-upstream-merge.py --version 0.0.2201

It fixes the files that conflict or drift in every fork merge, then stages them. A file it doesn't know
about is left untouched, and the script exits 2 so a person looks at it.

  CHANGELOG.md        keeps upstream's entries plus the fork-only ones (matched by their first `name`)
  gradle.properties   keeps the fork side, then sets version = <--version>
  DefaultCatalogTest  takes upstream on the count lines and unions the rest, then drops duplicate
                      imports and duplicate patch entries (git adds a patch twice when both sides put it
                      in different places) and rewrites the two counts from the lists themselves
  patches-list.json   fork side for now; :patches:generatePatchesList rewrites it afterwards
  patches-bundle.json fork side for now; the release step rewrites it afterwards
  README.md           fork side, with a warning; scripts/gen-readme-patches.py rewrites the table after

Copyright 2026 HushGram17 contributors. GPL-3.0-only.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

TEST = "patches/src/test/kotlin/app/morphe/util/DefaultCatalogTest.kt"
GENERATED = {"patches-list.json", "patches-bundle.json"}

HUNK = re.compile(
    r"^<<<<<<< [^\n]*\n(?P<ours>.*?)(?:^\|\|\|\|\|\|\| [^\n]*\n.*?)?^=======\n(?P<theirs>.*?)^>>>>>>> [^\n]*\n",
    re.S | re.M,
)
LIST_OPEN = re.compile(r"^\s*(?:private\s+)?val\s+(?:prior|neutral|optIn)\b.*listOf")
ENTRY = re.compile(r"^\s*([A-Za-z0-9_]+)\s*,?\s*(//.*)?$")


def git(*args):
    r = subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8")
    if r.returncode:
        sys.exit(f"git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def read(path):
    return Path(path).read_bytes().decode("utf-8")


def write(path, text):
    Path(path).write_bytes(text.encode("utf-8"))


def resolve_hunks(text, pick):
    return HUNK.sub(lambda m: pick(m.group("ours"), m.group("theirs")), text)


def bullet_key(line):
    m = re.search(r"`([^`]+)`", line)
    return m.group(1) if m else line[:60]


def pick_changelog(ours, theirs):
    upstream = {bullet_key(l) for l in theirs.splitlines() if l.startswith("* ")}
    fork_only = [l for l in ours.splitlines() if l.startswith("* ") and bullet_key(l) not in upstream]
    return "".join(l + "\n" for l in fork_only) + theirs


def fix_gradle(version):
    text = resolve_hunks(read("gradle.properties"), lambda ours, theirs: ours)
    text, n = re.subn(r"(?m)^version\s*=.*$", f"version = {version}", text)
    if n != 1:
        sys.exit("gradle.properties: expected exactly one 'version =' line")
    # The comment above it gives the scheme with the current version as its example; keep that true.
    maj, minor, code = (int(p) for p in version.split("."))
    upstream, fork = f"{maj}.{minor}.{code // 100}", code % 100
    text = re.sub(
        r"# \d+\.\d+\.\d+ is upstream \d+\.\d+\.\d+, fork build \d+ \(tag v[^)]*\)\.",
        f"# {version} is upstream {upstream}, fork build {fork} (tag v{upstream}-hg17.{fork}).",
        text,
    )
    write("gradle.properties", text)


def pick_test(ours, theirs):
    if "assertEquals(" in ours + theirs:
        return theirs  # the counts are rewritten below, so either side will do
    seen, out = set(), []
    for line in (ours + theirs).splitlines():
        if line.strip() not in seen:
            seen.add(line.strip())
            out.append(line)
    return "\n".join(out) + "\n"


def scan_entries(lines):
    entries, current = [], None
    for i, line in enumerate(lines):
        if LIST_OPEN.match(line):
            current = True
            continue
        if current:
            if re.match(r"^\s*\)\s*$", line):
                current = None
                continue
            m = ENTRY.match(line)
            if m:
                entries.append((i, m.group(1)))
    return entries


def fix_catalog_test(notes):
    text = resolve_hunks(read(TEST), pick_test)
    lines, seen = [], set()
    for line in text.split("\n"):
        if line.startswith("import "):
            if line in seen:
                notes.append(f"dropped duplicate import: {line}")
                continue
            seen.add(line)
        lines.append(line)

    entries = scan_entries(lines)
    last = {name: i for i, name in entries}  # keep the last occurrence, where upstream puts new ones
    dupes = {i for i, name in entries if last[name] != i}
    for i in sorted(dupes):
        notes.append(f"dropped duplicate patch entry: {lines[i].strip().rstrip(',')}")
    lines = [l for i, l in enumerate(lines) if i not in dupes]

    # The counts follow the lists. Which list an entry is in decides neutral; all is the sum of the three.
    sizes, current, name = {"neutral": 0, "total": 0}, None, None
    for line in lines:
        m = re.match(r"^\s*(?:private\s+)?val\s+(prior|neutral|optIn)\b.*listOf", line)
        if m:
            current = m.group(1)
            continue
        if current:
            if re.match(r"^\s*\)\s*$", line):
                current = None
                continue
            if ENTRY.match(line):
                sizes["total"] += 1
                if current == "neutral":
                    sizes["neutral"] += 1

    text = "\n".join(lines)
    text, n1 = re.subn(r"assertEquals\(\d+, neutral\.size\)", f"assertEquals({sizes['neutral']}, neutral.size)", text)
    text, n2 = re.subn(
        r'(assertEquals\("every named patch needs one reviewed decision", )\d+(, all\.size\))',
        lambda m: f"{m.group(1)}{sizes['total']}{m.group(2)}",
        text,
    )
    if n1 != 1 or n2 != 1:
        notes.append("WARNING: could not find both count assertions in DefaultCatalogTest; check them by hand")
    notes.append(f"DefaultCatalogTest counts: neutral={sizes['neutral']}, all={sizes['total']}")
    write(TEST, text)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", required=True, help="the fork version for gradle.properties, e.g. 0.0.2201")
    args = ap.parse_args()

    conflicted = git("diff", "--name-only", "--diff-filter=U").split()
    touched, unknown, notes = {"gradle.properties", TEST}, [], []

    for f in conflicted:
        if f in GENERATED:
            git("checkout", "--ours", "--", f)
            touched.add(f)
        elif f == "CHANGELOG.md":
            write(f, resolve_hunks(read(f), pick_changelog))
            touched.add(f)
        elif f == "README.md":
            write(f, resolve_hunks(read(f), lambda ours, theirs: ours))
            touched.add(f)
            print("::warning::README.md conflicted; kept the fork side. Review it against upstream's README.")
        elif f in ("gradle.properties", TEST):
            pass  # handled below, conflicted or not
        else:
            unknown.append(f)

    fix_gradle(args.version)
    fix_catalog_test(notes)

    for f in sorted(touched):
        if HUNK.search(read(f)):
            unknown.append(f)
    ok = sorted(touched - set(unknown))
    git("add", "--", *ok)

    for n in notes:
        print(n)
    print("resolved and staged:", ", ".join(ok))
    if unknown:
        print(f"::error::Conflicts this script doesn't know how to resolve: {', '.join(sorted(set(unknown)))}")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
