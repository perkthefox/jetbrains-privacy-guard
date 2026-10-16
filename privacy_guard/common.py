"""Shared build and installation utilities; no network operations."""

from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess
import tempfile


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def atomic_write(path: Path, data: bytes, mode: int | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError(f"Refusing to replace a symbolic link: {path}")
    permissions = mode if mode is not None else (path.stat().st_mode & 0o777 if path.exists() else 0o600)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, permissions)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def java_tools(java_home: str | Path | None = None, compiler: bool = False) -> dict[str, Path]:
    home = java_home or os.environ.get("JAVA_HOME")
    names = ["java", "javac"] if compiler else ["java"]
    tools = {}
    for name in names:
        executable = Path(home).expanduser() / "bin" / name if home else Path(shutil.which(name) or name)
        if not executable.is_file():
            raise ValueError("JDK 25 is required. Set JAVA_HOME or pass --java-home.")
        tools[name] = executable.resolve()
    result = subprocess.run([str(tools["java"]), "-version"], capture_output=True, text=True, check=True)
    match = re.search(r'version "(\d+)', result.stderr + result.stdout)
    if not match or int(match.group(1)) != 25:
        raise ValueError("This release supports Java 25. Check the IDE runtime as well as JAVA_HOME.")
    return tools


def property_value(value: str) -> str:
    """Escape an individual java.util.Properties value read through a UTF-8 Reader."""
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
