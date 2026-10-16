# Preparing a release

## Build and review locally

```sh
python3 scripts/project.py test
python3 scripts/project.py check
python3 scripts/project.py package
```

The archive is `dist/jetbrains-privacy-guard-VERSION.zip` with a SHA-256 sidecar. It contains the public source, documentation, synthetic tests and this project's built JAR. The packager uses an explicit list of public roots and never includes IDE reports, backups or vendor archives.

For a version change, update `VERSION`, the Java `VERSION` constant and the changelog together. Deterministic archive metadata removes timestamps; reproducible bytes still require the same source and compiler toolchain.

Run the verifier against relevant locally installed plugin versions. A real-IDE smoke test remains separate from fixture tests. Record its runtime and plugin versions in `compatibility.md`, without copying private evidence files.

## GitHub repository

Repository: [perkthefox/jetbrains-privacy-guard](https://github.com/perkthefox/jetbrains-privacy-guard).

Description:

> Local privacy guard for JetBrains IDE plugins. Blocks known telemetry and environment checks while keeping AI features enabled.

Suggested topics: `jetbrains`, `webstorm`, `privacy`, `java-agent`, `instrumentation`, `telemetry`.

The repository does not require secrets. The CI workflow has read-only contents permissions, uses pinned action revisions and uploads only the prepared distribution. It does not create GitHub releases or push code.

Push the reviewed source and inspect the Actions run for that commit. Publish a release only after reviewing the archive and checksums; attach the ZIP and its `.sha256` file. Release notes belong in `docs/releases/` and should describe the version's concrete behavior and validation.

The README's workflow badge shows GitHub's current result. Documentation links within the source also work in a local checkout.

## Useful references

Workflow configuration follows the official [setup-java](https://github.com/actions/setup-java), [setup-python](https://github.com/actions/setup-python), [checkout](https://github.com/actions/checkout) and [upload-artifact](https://github.com/actions/upload-artifact) actions. Recheck their pinned revisions when updating CI.
