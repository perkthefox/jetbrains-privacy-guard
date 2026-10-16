# Security and privacy reports

The current code targets the inspected mechanisms listed in [compatibility](docs/compatibility.md). It is not a complete plugin sandbox or a guarantee of zero data transmission.

For a new bypass or an unexpected transmission, report the agent version, IDE/runtime version, plugin ID/version and the smallest reproducible observation. Distinguish actual traffic from a URL or method name found in a binary.

Use GitHub's private vulnerability-reporting option if it is enabled on the repository. Otherwise, start with a minimal issue that contains no private content. Do not attach credentials, source-code prompts, full IDE logs, installed plugin archives, raw network captures or license material.

For a startup failure, first follow [recovery](docs/installation.md#recovery). Include only the relevant, redacted `BLOCKED_PLUGIN` or `BLOCKED_CLASS` entry.

The project does not collect reports automatically and has no remote rule-update service.
