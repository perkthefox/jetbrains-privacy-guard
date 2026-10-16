# Compatibility and evidence

## Baseline

The project began with a local audit of **WebStorm 2026.2.3**, build **WS-262.10968.77**, on macOS Apple Silicon with **JetBrains Runtime 25.0.4**.

| Component | Inspected version | Evidence |
| --- | --- | --- |
| Extra Icons | 2026.1.15.1 | Offline transformation and live class loading |
| Extra ToolWindow Colorful Icons | 2026.1.15 | Offline transformation and live class loading |
| Rainbow Brackets | 2026.2.1 | Offline transformation and live class loading |
| Material Theme UI | 13.1.0 | Offline transformation and live class loading |
| GitHub Copilot | 1.19.0-261-macos-arm64 | Offline transformation, live class loading, telemetry setting and bridge checks |
| JetBrains AI Assistant | 262.10968.223 | Offline transformation and live class loading |
| Junie | 262.2144.120 | Offline transformation of both analytics copies; lazy analytics classes were not seen in the idle live session |

These are observations from the original local 1.0.3 agent, not a promise about every build with the same marketing name. The repository release 1.1.0 keeps those transformation rules and makes paths, build tooling and installation portable. Plugin scoping and update detection now follow configured roots even when a directory is not named `plugins`; synthetic tests cover that layout.

## What was checked

- The original local scan covered 336,346 class entries in 1,431 JARs and verified 236 transformed classes.
- A live session recorded 35 transformed classes and no guard transformation errors or blocked protected families.
- Copilot model-list initialization succeeded. A private-source AI completion was not used as an end-to-end test after the changes.
- A roughly two-minute JVM socket recording contained no known telemetry hosts. This was bounded observation, not decrypted traffic inspection or complete native-process coverage.
- Repository tests use synthetic plugins, so anyone can run them without obtaining or publishing the inspected commercial archives.
- Repository release 1.1.0 was verified locally against the installed snapshot: 236 transformed classes passed verification, with no blocked protected families. A separate run on original, unmodified Extra Icons/Extra TCI archives verified all six affected classes. It does not depend on the prototype's earlier on-disk browser edits.

Raw logs, recordings, installed binaries and local paths are intentionally excluded from this repository. Aggregate historical counts are context; they are not current CI results. Run the local verifier for your installed versions.

## Platform status

| Environment | Status |
| --- | --- |
| WebStorm 2026.2.3 / macOS Apple Silicon / JBR 25 | Historical real-IDE baseline |
| Current repository / JDK 25 | Local build, JVM fixture and installer validation |
| macOS and Linux CI | JDK 25 with Python 3.11 / 3.14; [current workflow results](https://github.com/perkthefox/jetbrains-privacy-guard/actions/workflows/ci.yml) |
| Linux real IDE | Installer layout supported; real-IDE validation pending |
| Other JetBrains IDEs or custom plugin layouts | Explicit preflight and runtime validation required |
| Windows installer | Not implemented |
| Java 21 or another runtime major | Unsupported by the current build/install tools |

## Interpreting success

`blocked=[]` means all required categories for recognized protected families were found. Unknown families have no such category guarantee. Generic direct JVM-argument redaction still applies to recognized plugin code, but it is not a telemetry audit of every plugin.

New paths can coexist with old patterns. Their presence will not necessarily make preflight fail. Reflection, alternate transports, native code, core IDE telemetry and future independent mechanisms remain separate audit work.
