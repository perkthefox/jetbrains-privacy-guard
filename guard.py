#!/usr/bin/env python3
"""Command-line entry point for the local installer and recovery tools."""

import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

if sys.version_info < (3, 11):
    raise SystemExit("Python 3.11 or newer is required.")

from privacy_guard.install import main

if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
