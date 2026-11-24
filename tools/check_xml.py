#!/usr/bin/env python3
"""Verify that the Android XML resources are well formed."""

from __future__ import annotations

import pathlib
import sys
import xml.etree.ElementTree as ET

for stream in (sys.stdout, sys.stderr):
    reconfigure = getattr(stream, "reconfigure", None)
    if callable(reconfigure):
        reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
files = sorted(
    path
    for path in ROOT.glob("android/**/*.xml")
    if not {"build", ".toolchain"} & set(path.relative_to(ROOT).parts)
)

errors: list[tuple[pathlib.Path, str]] = []

for path in files:
    try:
        ET.parse(path)
    except ET.ParseError as error:
        errors.append((path, str(error)))

if errors:
    for path, error in errors:
        print(f"FAIL  {path.relative_to(ROOT)} — {error}")
    print(f"\nRESULT: {len(errors)} of {len(files)} XML files are invalid.")
    sys.exit(1)

print(f"PASS  XML is well formed — {len(files)} files verified.")
