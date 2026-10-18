<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# fix: evidence publication compliance

## Overview and motivation

Extract the pure publication compliance policy needed by #1957. Recovery must
respect recorded findings after the original values have been redacted, and
preparation must not merge unredacted recorded findings back into clean content.
This implements N6 §7.2 without inventing a second secret classification policy.

## Changes in detail

Combine fresh and historical findings. Require sensitivity, PII and protected
treatment declarations when their finding types demand them. Apply shared
redaction after all compliance metadata is assembled, including finding metadata.
Keep classification in the scanner and orchestration in a small pure namespace.

## Testing plan

Exercise fresh secrets, already-redacted findings, false declarations, encrypted
treatment, and secrets embedded in recorded finding fields and metadata. Run
component consumers, packaged tests, normal hooks and adversarial standards review.
Serialize all local test/build jobs across sibling worktrees.

## Deployment plan and related PRs

Base: main. No deployment or new public API. #1957 integrates this policy before
sealing and at recovery. Merge only after current-head review and all CI settle.

## Checklist

- [x] All three evidence consumers pass; rebuilt packaged regressions pass
  6 tests and 46 assertions.
- [ ] Standards, review comments and all CI checks are clean.
