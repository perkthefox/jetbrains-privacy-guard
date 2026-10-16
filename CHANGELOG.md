# Changelog

## 1.1.0 — 2026-10-16

First repository-ready release, based on the locally validated 1.0.3 agent.

- Portable source layout and explicit IDE library configuration.
- Plugin scoping and update checks use configured roots, including directories not named `plugins`.
- Dependency-free JDK 25 build with deterministic JAR/ZIP metadata and SHA-256 sidecars.
- Reversible installer with dry run, offline verification, status, disable and rollback.
- Synthetic JVM fixtures and isolated installation tests.
- English/Russian documentation, compatibility limits and recovery instructions.
- GitHub Actions checks and build artifacts; no automatic public release publishing.
- MIT license for the project's original source and documentation.

The telemetry and inspection transformation rules are inherited from the local agent. The installed local prototype is not automatically upgraded by preparing this repository.
