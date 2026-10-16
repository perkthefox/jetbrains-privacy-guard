#!/usr/bin/env python3
"""Build, test, check and package the project without downloading dependencies."""

from pathlib import Path
import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
if sys.version_info < (3, 11):
    raise SystemExit("Python 3.11 or newer is required.")
sys.path.insert(0, str(ROOT))
from privacy_guard.common import java_tools, sha256  # noqa: E402

PUBLIC_ROOTS = (
    "src", "privacy_guard", "scripts", "tests", "docs", "examples", ".github",
    "README.md", "README.ru.md", "LICENSE", "CHANGELOG.md", "CONTRIBUTING.md",
    "SECURITY.md", "VERSION", "guard.py", ".gitignore", ".gitattributes", ".editorconfig",
)


def public_files():
    for name in PUBLIC_ROOTS:
        path = ROOT / name
        candidates = sorted(path.rglob("*")) if path.is_dir() else [path]
        for candidate in candidates:
            if candidate.is_file() and "__pycache__" not in candidate.parts and candidate.suffix != ".pyc":
                yield candidate


def write_zip(path, entries):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    path.with_suffix(path.suffix + ".sha256").write_text(f"{sha256(path.read_bytes())}  {path.name}\n", encoding="utf-8")


def build(java_home=None):
    tools = java_tools(java_home, compiler=True)
    version = (ROOT / "VERSION").read_text().strip()
    source = ROOT / "src/main/java/local/privacyguard/PrivacyGuard.java"
    if f'String VERSION = "{version}";' not in source.read_text():
        raise ValueError("VERSION and the agent VERSION constant disagree")
    classes = ROOT / "build/classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    sources = sorted((ROOT / "src/main/java").rglob("*.java"))
    subprocess.run([str(tools["javac"]), "--release", "25", "-encoding", "UTF-8", "-d", str(classes), *map(str, sources)], check=True)
    manifest = (
        "Manifest-Version: 1.0\r\n"
        "Premain-Class: local.privacyguard.PrivacyGuard\r\n"
        "Main-Class: local.privacyguard.PrivacyGuard\r\n"
        "Can-Redefine-Classes: false\r\n"
        "Can-Retransform-Classes: false\r\n"
        f"Implementation-Version: {version}\r\n\r\n"
    ).encode()
    jar = ROOT / "build/privacy-guard.jar"
    entries = [("META-INF/MANIFEST.MF", manifest)]
    entries += [(p.relative_to(classes).as_posix(), p.read_bytes()) for p in classes.rglob("*.class")]
    write_zip(jar, entries)
    print(f"Built {jar.relative_to(ROOT)} ({jar.stat().st_size} bytes)")
    return jar


def test(java_home=None):
    tools = java_tools(java_home, compiler=True)
    build(java_home)
    environment = dict(os.environ, JAVA_HOME=str(tools["java"].parent.parent))
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"], cwd=ROOT, env=environment, check=True)


def check():
    """Check local links and reject common private artifacts before packaging."""
    problems = []
    files = list(public_files())
    if (ROOT / ".git").exists():
        tracked = subprocess.run(
            ["git", "ls-files", "-z"], cwd=ROOT, check=True,
            capture_output=True, text=True,
        ).stdout.split("\0")
        allowed = {p.relative_to(ROOT).as_posix() for p in files}
        for name in tracked:
            if name and name not in allowed:
                problems.append(f"Tracked file outside public roots: {name}")
    sensitive = ["/" + r"Users/[^/\s]+/", "/" + r"home/(?!example/)[^/\s]+/", r"-----BEGIN [A-Z ]*PRIVATE KEY-----", r"gh[pousr]_[A-Za-z0-9]{30,}", r"github_pat_[A-Za-z0-9_]{50,}"]
    for path in files:
        relative = path.relative_to(ROOT)
        if path.is_symlink():
            problems.append(f"Symlink in public files: {relative}")
        if path.suffix.lower() in {".jar", ".class", ".jfr", ".log", ".zip"} or ".javap." in path.name:
            problems.append(f"Private/binary artifact: {relative}")
        text = path.read_text(encoding="utf-8")
        if any(re.search(pattern, text) for pattern in sensitive):
            problems.append(f"Possible private content: {relative}")
        if path.suffix == ".md":
            targets = re.findall(r"!?\[[^\]]*\]\(([^)]+)\)", text)
            targets += re.findall(r'(?:src|href)="([^"]+)"', text)
            for target in targets:
                if target.startswith(("http:", "https:", "#", "mailto:")):
                    continue
                target = target.split("#", 1)[0]
                if target and not (path.parent / target).exists():
                    problems.append(f"Broken local link: {relative} -> {target}")
    if problems:
        raise ValueError("\n".join(problems))
    print(f"Checked {len(files)} public files: local links and private-artifact checks passed")


def package(java_home=None):
    check()
    jar = build(java_home)
    version = (ROOT / "VERSION").read_text().strip()
    prefix = f"jetbrains-privacy-guard-{version}/"
    entries = [(prefix + p.relative_to(ROOT).as_posix(), p.read_bytes()) for p in public_files()]
    entries += [(prefix + "build/" + p.name, p.read_bytes()) for p in (jar, jar.with_suffix(".jar.sha256"))]
    archive = ROOT / "dist" / f"jetbrains-privacy-guard-{version}.zip"
    write_zip(archive, entries)
    print(f"Packaged {archive.relative_to(ROOT)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["build", "test", "check", "package"])
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    if args.command == "check":
        check()
    else:
        globals()[args.command](args.java_home)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
