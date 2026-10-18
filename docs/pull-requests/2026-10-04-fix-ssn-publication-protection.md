<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# fix: Protect SSNs throughout evidence content

## Purpose

Make the shared redactor exclude plaintext dashed SSNs as N6 section 7.2 requires.
Classify recorded SSN findings as requiring protected treatment. This foundational
slice resolves the shared-policy finding on #1960 without exceeding its PR budget.
Base: main; no dependency on the pending event observability changes.

## Contract and review

Both redaction and scanning use digit boundaries, not regex word boundaries.
An adjacent `_` must not hide an SSN in values, map keys or metadata.
Longer digit groups are not the same identifier shape. Redaction preserves
surrounding non-sensitive text and is idempotent; findings contain no matched data.
The original scanner traversal and protected-treatment constructors are reused.

## Verification

Require focused SSN, original-value scanning and publication-compliance tests.
Run all consumers of redaction and evidence-bundle serially, rebuild the CLI,
verify the packaged tests, and scan the root against Miniforge standards.
Normal signed hooks, exact-head review and all CI including Build gate merge.
Preserve this branch and worktree. The export-boundary follow-up remains in #1960.
