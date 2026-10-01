<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Validate evidence at presentation and export boundaries

## Scope

Require a complete verified seal before CLI list/show or CLI export. Canonical
validation (#1959), format renderers (#1975), and manager export enforcement
(#1977) are merged prerequisites; this PR owns the CLI consumers.
Report invalid, tampered, and unsealed bundles without presenting their ordinary evidence details.
Do not silently repair legacy evidence or overwrite an export destination on validation failure.
Distinguish existing unreadable files from absent sources; show/export refuse the
former explicitly rather than reporting them as missing.
Only EDN source files enter directory validation; JSON/HTML exports and unrelated
files do not make an otherwise empty evidence directory appear invalid.
Serialize the exact validated value instead of rereading a mutable source file.
Use canonical EDN and preserve nanosecond instant precision when reading it back.
Use the bounded public file reader from #1961; reject trailing forms and input
larger than 16 MiB before parsing. Published fixtures include event links and tier.
Export canonical EDN, lossless Transit JSON, or escaped HTML through the shared
format adapters, always using UTF-8. Honor the requested positional output path
and preserve the default destination for existing programmatic callers. Encode
before creating directories or writing files; renderer failure leaves existing
destinations unchanged. Reject unsupported formats explicitly.
This implements the consumer-boundary finding from #1957.
The shared publication validator also refuses correctly hashed evidence with
exposed secrets or understated sensitivity, PII, or treatment declarations.
It checks the policy from merged #1981 without modifying or resealing evidence.
Shared redaction excludes plaintext SSNs from values, keys, and metadata, and
recorded SSN findings require protected treatment. Truthful flags or a claimed
redacted/encrypted treatment never authorize exporting an exposed SSN.

## Standards adversarial pass

Share the published-evidence predicate through the public component interface.
Separate validation, diagnostics, and filesystem effects into named stages.
Keep exception handling at named boundaries; preserve interruption and fatal errors.
Reuse one test bundle constructor and remove anonymous display configuration callbacks.
Construct display defaults from the active catalog and reuse common field options.
Keep every namespace within three strata and every commit below its reportable budget.

## Verification

Packaged CLI and manager tests verify valid round trips and rejection before export writes.
Tampering and missing seals leave existing destinations unchanged.
Evidence and redaction consumers and a CLI build pass. CLI regressions pass
20 tests / 81 assertions; packaged CLI, publication, compliance and SSN coverage
passes 31 tests / 199 assertions. The root standards scan reports no violations.
Run normal hooks before each commit and refresh verification before merge.
Current-head review and all CI, including Build, remain mandatory merge gates.
Legacy nil/false detail fields retain canonical fallbacks, including status,
phase names and artifacts. Failure attribution selects the first truthy source.
CLI entry-point strata prerequisite #1980 enables the positional parser fix.
Command-line regressions cover explicit paths (including spaces), the EDN
default, and JSON/HTML format flags. No deployment is required.
