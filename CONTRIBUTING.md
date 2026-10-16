# Contributing

Use JDK 25 and Python 3.11+. The project has no package-manager dependencies.

```sh
python3 scripts/project.py test
python3 scripts/project.py check
```

## Adding or changing a rule

Describe the exact plugin version and behavior being changed. Separate a local observation from an inference about purpose. Reading JVM arguments alone does not establish telemetry, and a string containing a URL alone does not establish a network request.

Keep matching narrow enough to preserve unrelated behavior. Add a synthetic fixture that exercises the observed contract and proves the intended side effect does not occur. Also check a nearby allowed path. A fixture must be original code, not copied decompiled implementation.

For a new protected family, add its identity mapping, candidate filter and required rule categories. Document what an unknown version does. Verify on locally obtained archives when available, then summarize the result without committing those archives or raw reports.

## Repository boundaries

Commit source, synthetic fixtures and sanitized documentation. Keep vendor JARs, native binaries, license material, IDE settings, logs, JFR recordings and decompiled listings outside the repository. `reports/`, `local/`, `runtime/`, `build/` and `dist/` are ignored.

Use [the architecture notes](docs/architecture.md) to locate the rule and failure paths. Keep the English and Russian README aligned when changing user-visible behavior.

## Pull requests

Explain the concrete behavior before and after the change, the supported version/contract and the validation performed. Do not describe a passing fixture test as real-IDE validation. If the rule fails on an unrecognized structure, show that the affected sensitive code cannot execute.
