"""Reversible installation for a single JetBrains IDE profile on macOS/Linux."""

from pathlib import Path
import datetime
import json
import os
import platform
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from .common import atomic_write, java_tools, property_value, sha256

SCHEMA = 1
PROPERTIES = {
    "extra-tools-pack.data-sharing": "false",
    "llm.jcp.analytics.enabled": "false",
    "llm.chat.agent.event.telemetry.enabled": "false",
    "llm.opentelemetry.trace.enable": "false",
    "otel.sdk.disabled": "true",
}
XML_OPTIONS = (
    ("github-copilot.xml", "github-copilot", "enableTelemetry", "false"),
    ("github-copilot.xml", "github-copilot", "fetchStrategy", "Client"),
    ("material_theme_new.xml", "MaterialThemeNewConfig", "allowDataCollection", "false"),
)
DEFAULT_INSTALL = Path.home() / ".local/share/jetbrains-privacy-guard"


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def resolve_ide(ide_home=None, config_dir=None, plugins_dir=None, vmoptions=None, java_home=None):
    system = platform.system()
    if system not in {"Darwin", "Linux"}:
        raise ValueError("The installer currently supports macOS and Linux only.")
    if ide_home is None:
        candidates = [p for p in (Path("/Applications/WebStorm.app"), Path.home() / "Applications/WebStorm.app") if p.exists()]
        if len(candidates) != 1:
            raise ValueError("Pass --ide-home explicitly; a unique WebStorm installation was not found.")
        ide_home = candidates[0]
    home = Path(ide_home).expanduser().resolve()
    metadata_candidates = [home / "Contents/Resources/product-info.json", home / "product-info.json"]
    metadata_path = next((p for p in metadata_candidates if p.is_file()), None)
    if metadata_path is None:
        raise ValueError("No product-info.json found under --ide-home.")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    os_name = "macOS" if system == "Darwin" else "Linux"
    arch = {"arm64": "aarch64", "AMD64": "amd64", "x86_64": "amd64"}.get(platform.machine(), platform.machine())
    launches = [entry for entry in metadata["launch"] if entry["os"] == os_name]
    launch = next((entry for entry in launches if entry.get("arch") in {arch, platform.machine()}), None)
    if launch is None:
        raise ValueError("The IDE does not have a launcher for this OS/architecture.")
    contents = metadata_path.parent.parent if home.suffix == ".app" else home
    profile = metadata["dataDirectoryName"]
    if system == "Darwin":
        default_config = Path.home() / "Library/Application Support/JetBrains" / profile
        default_plugins = default_config / "plugins"
    else:
        default_config = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "JetBrains" / profile
        default_plugins = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "JetBrains" / profile
    config = Path(config_dir).expanduser().resolve() if config_dir else default_config
    if not config.is_dir():
        raise ValueError("The config directory does not exist. Start the IDE once or pass --config-dir.")
    launcher = (metadata_path.parent / launch["launcherPath"]).resolve()
    defaults = (metadata_path.parent / launch["vmOptionsFilePath"]).resolve()
    environment_key = metadata.get("envVarBaseName", "IDEA") + "_VM_OPTIONS"
    if os.environ.get(environment_key) and not vmoptions:
        raise ValueError(f"{environment_key} overrides VM options. Pass that file using --vmoptions.")
    custom_vm = Path(vmoptions).expanduser().absolute() if vmoptions else config / defaults.name
    runtime = (metadata_path.parent / launch["javaExecutablePath"]).resolve().parent.parent
    runtime_override = config / (launcher.stem + ".jdk")
    if runtime_override.exists() and not java_home:
        raise ValueError("A custom IDE runtime is configured. Pass its directory using --java-home.")
    if java_home:
        runtime = Path(java_home).expanduser().resolve()
    user_plugins = Path(plugins_dir).expanduser().resolve() if plugins_dir else (config / "plugins" if system == "Darwin" else default_plugins)
    return {
        "name": metadata["name"], "version": metadata["version"], "profile": profile,
        "ide_home": str(home), "config": str(config), "vmoptions": str(custom_vm),
        "default_vmoptions": str(defaults), "java_home": str(runtime),
        "launcher": str(launcher), "ide_lib": str(contents / "lib"),
        "plugin_roots": [str(user_plugins), str(contents / "plugins")],
    }


def running(ide):
    result = subprocess.run(["ps", "-axo", "comm="], text=True, capture_output=True, check=True)
    expected = {ide["launcher"], str(Path(ide["java_home"]) / "bin/java")}
    return any(line.strip() in expected for line in result.stdout.splitlines())


def require_closed(ide):
    if running(ide):
        raise ValueError("Close the IDE normally first so it cannot overwrite the settings being changed.")


def validate_jar(jar):
    if not jar.is_file():
        raise ValueError("Agent JAR not found. Run python3 scripts/project.py build first.")
    with zipfile.ZipFile(jar) as archive:
        data = archive.read("local/privacyguard/PrivacyGuard.class")
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    if data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") != 69:
        raise ValueError("The agent must be built for Java 25.")
    if "Premain-Class: local.privacyguard.PrivacyGuard" not in manifest:
        raise ValueError("The JAR does not contain the expected Java agent manifest.")
    checksum = jar.with_suffix(jar.suffix + ".sha256")
    if checksum.exists() and checksum.read_text().split()[0] != sha256(jar.read_bytes()):
        raise ValueError("The JAR checksum does not match its sidecar.")
    return manifest.split("Implementation-Version: ")[-1].splitlines()[0]


def property_pattern(key):
    return re.compile(r"^[ \t]*" + re.escape(key) + r"(?:[ \t]*[=:][ \t]*|[ \t]+)([^\r\n]*)$", re.M)


def parse_xml(data):
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    root = ET.fromstring(data, parser=parser) if data else ET.Element("application")
    if root.tag != "application":
        raise ValueError("Unsupported IDE settings XML root")
    return root


def xml_option(root, component, name, create=False):
    parents = root.findall(f"./component[@name='{component}']")
    if len(parents) > 1:
        raise ValueError(f"Duplicate XML component: {component}")
    parent = parents[0] if parents else None
    if parent is None and create:
        parent = ET.SubElement(root, "component", {"name": component})
    if parent is None:
        return None, None
    entries = parent.findall(f"./option[@name='{name}']")
    if len(entries) > 1:
        raise ValueError(f"Duplicate XML option: {name}")
    entry = entries[0] if entries else None
    if entry is None and create:
        entry = ET.SubElement(parent, "option", {"name": name})
    return parent, entry


def xml_bytes(root):
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="utf-8") + b"\n"


def configuration_plan(ide, destination):
    agent_line = f"-javaagent:{destination / 'privacy-guard.jar'}={destination / 'guard.properties'}"
    vm = Path(ide["vmoptions"])
    old_vm = vm.read_bytes() if vm.exists() else Path(ide["default_vmoptions"]).read_bytes()
    vm_text = old_vm.decode("utf-8")
    if any(line.strip().startswith("-javaagent:") and "privacy-guard" in line for line in vm_text.splitlines()):
        raise ValueError("A privacy guard agent is already configured. Disable the previous installation first.")
    changes = {vm: vm_text.rstrip("\r\n").encode() + b"\n" + agent_line.encode() + b"\n"}
    properties = Path(ide["config"]) / "idea.properties"
    text = properties.read_text(encoding="utf-8") if properties.exists() else ""
    previous_properties = {}
    for key, value in PROPERTIES.items():
        pattern = property_pattern(key)
        matches = list(pattern.finditer(text))
        if len(matches) > 1:
            raise ValueError(f"Duplicate property {key}; resolve it before installation.")
        previous_properties[key] = matches[0].group(0) if matches else None
        if matches:
            text = pattern.sub(key + "=" + value, text)
        else:
            text = text.rstrip("\n") + ("\n" if text else "") + key + "=" + value + "\n"
    changes[properties] = text.encode()
    previous_xml = []
    for name, component, key, value in XML_OPTIONS:
        path = Path(ide["config"]) / "options" / name
        original = changes.get(path, path.read_bytes() if path.exists() else b"")
        root = parse_xml(original)
        _, entry = xml_option(root, component, key)
        previous_xml.append({"path": str(path), "component": component, "key": key,
                             "before": entry.get("value") if entry is not None else None, "after": value})
        _, entry = xml_option(root, component, key, create=True)
        entry.set("value", value)
        changes[path] = xml_bytes(root)
    return changes, agent_line, previous_properties, previous_xml


def write_transaction(changes):
    snapshots = {path: (path.read_bytes(), path.stat().st_mode & 0o777) if path.exists() else None for path in changes}
    written = []
    try:
        for path, data in changes.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                atomic_write(path, data)
            written.append(path)
    except BaseException:
        for path in reversed(written):
            prior = snapshots[path]
            if prior is None:
                path.unlink(missing_ok=True)
            else:
                atomic_write(path, prior[0], prior[1])
        raise


def verify(ide, jar, report):
    validate_jar(jar)
    tools = java_tools(ide["java_home"])
    subprocess.run([str(tools["java"]), "-jar", str(jar), "--verify", str(report),
                    "--ide-lib", ide["ide_lib"], *ide["plugin_roots"]], check=True)


def install(ide, jar, destination, dry_run=False):
    destination = destination.expanduser().absolute()
    if any(character in str(destination) for character in "=\n\r|"):
        raise ValueError("The install directory cannot contain =, | or line breaks.")
    for value in ide["plugin_roots"] + [ide["ide_lib"]]:
        if any(character in value for character in "\n\r|"):
            raise ValueError("Plugin/library paths cannot contain | or line breaks.")
    if destination.exists():
        raise ValueError("Install directory already exists. Use an empty destination; backups are never overwritten.")
    version = validate_jar(jar)
    java_tools(ide["java_home"])
    changes, agent_line, properties_before, xml_before = configuration_plan(ide, destination)
    if dry_run:
        print(json.dumps({"dry_run": True, "version": version, "ide": ide,
                          "install_dir": str(destination), "files_to_change": list(map(str, changes)),
                          "ide_running": running(ide)}, indent=2))
        return
    require_closed(ide)
    with tempfile.TemporaryDirectory(prefix="privacy-guard-preflight-") as temporary:
        report = Path(temporary) / "verification.tsv"
        verify(ide, jar, report)
        require_closed(ide)
        destination.mkdir(parents=True, mode=0o700)
        records = []
        for index, path in enumerate(changes):
            if path.is_symlink():
                raise ValueError(f"Refusing to replace a symbolic link: {path}")
            before = path.read_bytes() if path.exists() else None
            backup = destination / "backup" / f"{index:02d}-{path.name}"
            if before is not None:
                atomic_write(backup, before)
            records.append({"path": str(path), "existed": before is not None,
                            "mode": path.stat().st_mode & 0o777 if path.exists() else 0o600,
                            "backup": str(backup), "after_sha256": sha256(changes[path])})
        log = destination / "logs/guard.log"
        config = "plugin.roots=" + property_value("|".join(ide["plugin_roots"])) + "\n"
        config += "ide.lib=" + property_value(ide["ide_lib"]) + "\nlog=" + property_value(str(log)) + "\n"
        atomic_write(destination / "privacy-guard.jar", jar.read_bytes())
        atomic_write(destination / "guard.properties", config.encode())
        atomic_write(destination / "verification.tsv", report.read_bytes())
        state = {"schema": SCHEMA, "version": version, "installed_at": now(), "ide": ide,
                 "agent_line": agent_line, "agent_sha256": sha256(jar.read_bytes()), "log": str(log),
                 "files": records, "properties_before": properties_before, "xml_before": xml_before,
                 "state": "enabled"}
        encoded = (json.dumps(state, indent=2) + "\n").encode()
        atomic_write(destination / "pending-install.json", encoded)
        # Include the final ledger in the transaction so a ledger write failure also
        # restores the IDE configuration. A crash leaves backups and a pending file.
        changes[destination / "install.json"] = encoded
        write_transaction(changes)
        (destination / "pending-install.json").unlink()
    print(f"Installed {version} in {destination}. Start {ide['name']} to activate it.")


def load_state(destination):
    state = json.loads((destination / "install.json").read_text(encoding="utf-8"))
    if state.get("schema") != SCHEMA:
        raise ValueError("This installation uses a different ledger format; use its original management script.")
    return state


def status(destination):
    state = load_state(destination)
    vm = Path(state["ide"]["vmoptions"])
    enabled = vm.exists() and state["agent_line"] in vm.read_text(encoding="utf-8").splitlines()
    jar = destination / "privacy-guard.jar"
    print(json.dumps({"version": state["version"], "state": state["state"],
                      "configured_for_next_start": enabled,
                      "agent_checksum_matches": jar.exists() and sha256(jar.read_bytes()) == state["agent_sha256"],
                      "log": state["log"]}, indent=2))


def remove(destination, rollback=False):
    state = load_state(destination)
    if state["state"] == "rolled-back":
        print("Configuration was already rolled back. Backups are retained.")
        return
    require_closed(state["ide"])
    vm = Path(state["ide"]["vmoptions"])
    changes = {}
    if vm.exists():
        lines = vm.read_text(encoding="utf-8").splitlines(keepends=True)
        changes[vm] = "".join(line for line in lines if line.rstrip("\r\n") != state["agent_line"]).encode()
    properties = Path(state["ide"]["config"]) / "idea.properties"
    if rollback and properties.exists():
        text = properties.read_text(encoding="utf-8")
        for key, original in state["properties_before"].items():
            pattern = property_pattern(key)
            if pattern.findall(text) == [PROPERTIES[key]]:
                text = pattern.sub(lambda _: original or "", text)
        changes[properties] = text.encode()
    for item in state["xml_before"]:
        path = Path(item["path"])
        root = parse_xml(changes.get(path, path.read_bytes() if path.exists() else b""))
        parent, entry = xml_option(root, item["component"], item["key"])
        value = entry.get("value") if entry is not None else None
        if rollback and value not in {None, item["after"]}:
            continue  # Preserve values the user changed after installation.
        desired = item["before"] if rollback else item["after"]
        if desired is None:
            if entry is not None:
                parent.remove(entry)
        else:
            _, entry = xml_option(root, item["component"], item["key"], create=True)
            entry.set("value", desired)
        changes[path] = xml_bytes(root)
    # Restore byte-for-byte backups for files untouched since installation.
    # If subsequently edited, only the owned settings above are changed.
    if rollback:
        for record in state["files"]:
            path = Path(record["path"])
            if path.exists() and sha256(path.read_bytes()) == record["after_sha256"]:
                if record["existed"]:
                    changes[path] = Path(record["backup"]).read_bytes()
                else:
                    changes[path] = None
    state["state"] = "rolled-back" if rollback else "disabled"
    state["changed_at"] = now()
    changes[destination / "install.json"] = (json.dumps(state, indent=2) + "\n").encode()
    write_transaction(changes)
    print("Configuration rolled back; backups retained." if rollback else "Agent disabled; explicit privacy opt-outs retained.")


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description="Install and manage the local JetBrains Privacy Guard.")
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("install", "verify"):
        sub = commands.add_parser(command)
        sub.add_argument("--ide-home", type=Path)
        sub.add_argument("--config-dir", type=Path)
        sub.add_argument("--plugins-dir", type=Path)
        sub.add_argument("--vmoptions", type=Path)
        sub.add_argument("--java-home", type=Path)
        sub.add_argument("--jar", type=Path, default=Path(__file__).resolve().parents[1] / "build/privacy-guard.jar")
        if command == "install":
            sub.add_argument("--install-dir", type=Path, default=DEFAULT_INSTALL)
            sub.add_argument("--dry-run", action="store_true")
        else:
            sub.add_argument("--report", type=Path, default=Path("reports/verification.tsv"))
    for command in ("status", "disable", "rollback"):
        sub = commands.add_parser(command)
        sub.add_argument("--install-dir", type=Path, default=DEFAULT_INSTALL)
    args = parser.parse_args(argv)
    if args.command in {"install", "verify"}:
        ide = resolve_ide(args.ide_home, args.config_dir, args.plugins_dir, args.vmoptions, args.java_home)
        if args.command == "verify":
            verify(ide, args.jar.expanduser().resolve(), args.report)
        else:
            install(ide, args.jar.expanduser().resolve(), args.install_dir, args.dry_run)
    elif args.command == "status":
        status(args.install_dir.expanduser().absolute())
    else:
        remove(args.install_dir.expanduser().absolute(), rollback=args.command == "rollback")
