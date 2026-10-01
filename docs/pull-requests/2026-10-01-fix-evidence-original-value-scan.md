<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Complete original-value evidence scanning

## Scope and dependencies

Based on main. This scanner fix is a prerequisite of finalizer #1957. N6 section
7.2 requires detection of sensitive data; a truncated printed representation
cannot establish absence of SSNs or email addresses in the original value.

## Design and verification

Scan original scalar values and metadata without concatenating separate values.
Preserve the shared secret and payment-card detectors. Return finding types, never
matched values. The publication boundary already rejects unbounded or unsupported
input before scanning. Test deep and wide content, map keys and metadata, and
verify that no raw matched value leaks into the report.
Keep policy data separate from value traversal and scanner composition.

Run all evidence consumers, the CLI build and packaged scanner tests serially.
Require normal hooks, adversarial standards review, settled current-head Copilot
review and all CI checks before merge. No deployment step is required.
