<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# fix: Preserve redaction evidence in compliance declarations

## Overview and motivation

Base: main. Resolve the shared-policy gap found during #1957 review.
N3 section 8.2 makes the redaction marker an audit record of withheld content.
N6 section 7.2 requires sensitivity and redacted treatment to reflect that record.
Removing recorded findings must not make a remaining marker appear non-sensitive.

## Changes in detail

Recognize the shared marker through the existing original-value scanner, including
embedded strings, keys and metadata. Do not infer the removed secret's PII class.
Reuse this finding in preparation and read-only publication validation.
Existing markers require sensitive-data true and redacted treatment, even when
an input claims encryption. Marker-free encrypted evidence retains its treatment.
Reuse the existing traversal and shared redaction policy.
Deduplicate fresh and retained findings. Scan the redacted result during initial
preparation so newly created markers are recorded immediately, not on a retry.
Repeated preparation preserves both the content and its hash.

## Testing plan

Findings removal and rehashing are covered at the publication boundary.
The pending finalization regression fails against the old policy and passes
with this shared fix: 27 tests, 143 assertions. All three deployed evidence
consumers pass serially. The rebuilt CLI passes 24 tests and 335 assertions.
Older scanner tests now require exactly the marker finding after redaction.
The original secret findings must still disappear.
Repeat-preparation regressions cover markers, raw secrets, PII and contaminated
finding fields or metadata. The old implementation fails 17 assertions.

## Deployment plan

Ship with the normal CLI build. Previously understated retained bundles are
refused; no persisted data is rewritten. Gate merge on normal signed hooks,
zero standards violations, exact-head review and all CI including Build.
Preserve the branch and worktree.

## Related work

This shared fix unblocks retained-seal recovery in #1957.

## Checklist

- [x] Red/green recovery regression and rebuilt publication tests
- [x] Adversarial review of boundaries, default treatment and shared policy
- [ ] Signed hooks, standards scan and exact-head review/CI
