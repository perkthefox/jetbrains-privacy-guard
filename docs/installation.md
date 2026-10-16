# Installation and recovery

## Requirements

- Python 3.11 or newer for build/install tools.
- JDK 25 to compile and test; Java 25 in the IDE itself.
- A supported plugin layout. The real-IDE baseline is [documented separately](compatibility.md).
- Write access to your own IDE configuration. No `sudo` is needed.

The installer supports macOS and Linux layouts. macOS has real-IDE validation; Linux needs a real-IDE compatibility run. Windows installation is not implemented.

## Build

From the repository root:

```sh
export JAVA_HOME="/path/to/jdk-25"
python3 scripts/project.py test
python3 scripts/project.py check
```

On macOS, a compatible WebStorm distribution includes a JDK under `WebStorm.app/Contents/jbr/Contents/Home`. For example:

```sh
python3 scripts/project.py test \
  --java-home "/Applications/WebStorm.app/Contents/jbr/Contents/Home"
```

The output is `build/privacy-guard.jar` with a SHA-256 sidecar. A packaged release includes these files, so rebuilding is optional when using that release archive. Checksums detect accidental corruption; an unsigned sidecar does not authenticate a publisher.

## Preview and install

```sh
python3 guard.py install --ide-home "/Applications/WebStorm.app" --dry-run
```

The preview reports the selected profile, paths and files to change. It writes nothing. It checks the runtime and configuration shape but does not run the full bytecode verifier.

Close the IDE normally, then:

```sh
python3 guard.py install --ide-home "/Applications/WebStorm.app"
```

The installer performs offline bytecode verification before modifying the profile. It then:

1. Creates a private installation directory and configuration backups.
2. Copies the agent and writes its local configuration.
3. Adds one `-javaagent` line to custom VM options, keeping existing options and agents.
4. Writes the covered privacy opt-outs to `idea.properties` and plugin settings.
5. Records an installation ledger for status and rollback.

If the custom VM-options file is absent, the installer starts from the IDE's bundled defaults. A failed configuration write rolls back the writes from that transaction.

The default installation directory is `~/.local/share/jetbrains-privacy-guard`. Logs live in `logs/guard.log` beneath it and rotate after 8 MiB. Neither logs nor backups are uploaded.

## Explicit locations

For Toolbox or a manually installed app, pass the actual app directory. macOS auto-detection is deliberately limited to a single WebStorm found in `/Applications` or `~/Applications`.

```sh
python3 guard.py install \
  --ide-home "$HOME/Applications/WebStorm.app" \
  --config-dir "$HOME/Library/Application Support/JetBrains/WebStorm2026.2" \
  --plugins-dir "$HOME/Library/Application Support/JetBrains/WebStorm2026.2/plugins" \
  --install-dir "$HOME/.local/share/jetbrains-privacy-guard-webstorm" \
  --dry-run
```

On Linux, pass `--ide-home` pointing to the extracted IDE directory. Default profile locations respect `XDG_CONFIG_HOME` and `XDG_DATA_HOME`.

If you use an environment override such as `WEBIDE_VM_OPTIONS`, pass its actual file with `--vmoptions`. For a custom boot runtime, pass its directory with `--java-home`. The installer reads `product-info.json` and checks the selected runtime, but cannot infer every launcher wrapper or custom `idea.config.path`/`idea.plugins.path`; supply those paths explicitly.

For multiple IDE profiles, use a separate `--install-dir` for each. Pass the same directory to `status`, `disable` and `rollback`.

## Verify without installation

This reads installed JARs but does not execute plugin code or modify the profile:

```sh
python3 guard.py verify \
  --ide-home "/Applications/WebStorm.app" \
  --report reports/verification.tsv
```

The report includes local archive paths and method identifiers. Keep it local or redact paths before sharing. `reports/` is ignored by Git and excluded from release archives.

## Check a running installation

```sh
python3 guard.py status
```

`configured_for_next_start` means the startup line is present. It does not prove the current process loaded the agent. Look for a recent `START` and `READY` in the local log; `PATCH` entries name transformed classes, while `BLOCKED_PLUGIN` and `BLOCKED_CLASS` explain compatibility failures.

The agent records method names and archive paths, but does not log request bodies, keys or JVM-argument values. The broad JVM-argument rule can affect plugin diagnostics that legitimately read those arguments.

## Recovery

Close the IDE normally before changing configuration:

```sh
python3 guard.py disable
```

This removes the startup line and explicitly saves the covered plugin opt-outs. Those values can otherwise be omitted by the IDE because the agent changed their defaults.

To restore this installation's original settings:

```sh
python3 guard.py rollback
```

Unmodified files are restored byte-for-byte. For files edited after installation, rollback changes only owned options whose values are still recognizable, preserving unrelated edits. Backups and the installation directory remain for inspection.

If you cannot run the tool, edit the custom VM-options file and remove only the line whose agent path points to **this installation's `privacy-guard.jar`**. Keep other `-javaagent` lines. The agent directory and IDE settings remain on disk until you remove or restore them yourself.

An interrupted install may leave `pending-install.json` and `backup/`. Do not overwrite that directory: compare its listed files with the backups before repairing or retrying in a fresh destination.

## Updates

Plugin updates are checked as changed JARs are encountered during class loading, and at the next IDE start. Already loaded classes are not retransformed; restart when the IDE requests it.

The agent does not update itself. For a new guard release, close the IDE, disable the old guard, and install the new release into a fresh installation directory. Keep the previous directory for recovery. The new rollback baseline is the configuration at the time of that new installation.

Changing the IDE profile or Java runtime needs separate verification. This release supports Java 25; it does not claim compatibility with Java 21 or future runtime versions.

The earlier private prototype used a different installation ledger. Its original `manage_guard.py` must handle its removal. This installer refuses to overwrite existing installations or manage an unknown ledger format.
