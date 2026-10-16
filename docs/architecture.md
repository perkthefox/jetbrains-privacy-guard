# Architecture

## Two parts, no service

`src/main/java/local/privacyguard/PrivacyGuard.java` contains the startup agent, rule matcher, transformer and offline verifier. It uses the JDK 25 Class-File API and `java.lang.instrument`; the JAR has no external dependencies.

`guard.py` and `privacy_guard/` implement local installation, preflight, status, disabling and rollback. They use the Python standard library. They are not resident processes and do not download code.

## Startup and identification

1. `premain` reads the supplied UTF-8 properties file and sets the covered opt-out properties.
2. `preflight` examines known plugin archives without executing their classes. It gathers matched rule categories and checks each protected family's required categories.
3. The transformer is registered before the application starts loading plugin classes.
4. A class is associated with a plugin using its archive location and, where available, IntelliJ's `PluginClassLoader.getPluginId()`. Resource lookup handles loaders with no `CodeSource`.

Some archive discovery still relies on known plugin directory names. A new layout can prevent recognition or cause a protected family to be blocked. Supply the correct plugin roots and inspect the preflight output; successful startup alone is not a complete audit.

## Transformations

The rules use a mixture of explicit entry-point names, method descriptors, field contracts and call patterns. Obfuscated Extra/Rainbow code is matched using retained callback names and the inspected call patterns.

- Known senders and inspection routines receive type-correct empty/no-op implementations.
- Direct plugin calls to `RuntimeMXBean.getInputArguments()` are replaced with `Collections.emptyList()` while preserving the surrounding method.
- Copilot telemetry fields are forced to `OFF`/`false`, and its supported HTTP fetch strategy is forced to `Client`.
- The Copilot IDE HTTP bridge checks selected telemetry hosts before executing its original transport. A blocked request completes its response stream locally and returns HTTP 403. Allowed requests follow the original code.

The bytecode emits calls to standard-library or existing plugin classes. It does not require a plugin class loader to resolve classes from this agent.

## Copilot HTTP boundary

The filter is scoped to the covered Copilot bridge, not all network access in the IDE. It recognizes:

| Match | Hosts |
| --- | --- |
| Prefix and suffix | `telemetry.*.githubcopilot.com` |
| Exact | `copilot-telemetry.githubusercontent.com`, `copilot-telemetry.github.com` |
| Exact | `api.mixpanel.com`, `api-eu.mixpanel.com`, `dc.services.visualstudio.com` |
| Suffix | `.applicationinsights.azure.com`, `.applicationinsights.azure.cn`, `.ingest.sentry.io` |

Host matching is case-insensitive. URLs outside these rules retain the original transport behavior. The agent does not rewrite the packaged native Copilot executable.

## Update handling and failure behavior

Archive size and modification time identify ordinary on-disk changes. A changed archive triggers another preflight before a protected class is allowed to load. This is an update detector, not cryptographic integrity checking or isolation from a hostile plugin.

Each protected family has required rule categories. If a category disappears, a required field/method contract changes or transformation fails, the agent refuses affected class definitions. It deliberately returns invalid class bytes: the JVM ignores transformer exceptions, which would otherwise allow the original sensitive code to execute.

This can produce IDE plugin errors. It does not guarantee that every unrelated class or new independent telemetry path in that plugin was detected. Classes already loaded are not retroactively changed. A failure to initialize the agent itself aborts JVM startup.

## Validation

The offline verifier transforms candidate classes and calls `ClassFile.verify`. It reads supplied plugin archives and optional IDE libraries, writes a local TSV, and exits nonzero for unsupported protected structures or verification errors.

Synthetic JVM tests exercise observable behavior: no side effects in blocked sinks, normal function results, the ordinary fixture license check, native telemetry state, allowed HTTP behavior, completed blocked responses, a null-`CodeSource` plugin loader and an unrecognized hot update.

Installer tests cover file preservation, defaults, dry runs, rollback, running-IDE refusal, failed writes and path handling. CI never downloads or redistributes commercial plugins.

## Configuration

The installer writes the real absolute paths. The [example](../examples/guard.properties.example) uses placeholders only.

| Key | Meaning |
| --- | --- |
| `plugin.roots` | Absolute plugin directories separated by `\|` |
| `ide.lib` | Optional IDE library directory for resolving class hierarchies during verification |
| `log` | Absolute local log filename |

The agent does not expand `$HOME` or `~` inside the properties file. Paths containing a literal pipe are unsupported by the root-list format.
