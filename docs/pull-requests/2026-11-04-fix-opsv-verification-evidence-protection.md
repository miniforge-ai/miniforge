<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Protect OPSV verification evidence before storage

## Overview

Protect verification observations before publishing evidence or returning phase
output. Keep the original in-memory comparison inputs independent of reporting.

## Motivation

A packaged reproduction with a fake password found unprotected content in both
preparation and final artifacts, and in the phase output. N6 section 7.2 requires
scanning and redaction before storage, not just when sealing the final bundle.

## Changes in Detail

Reuse the evidence component's shared scanning, redaction and compliance policy
when constructing phase artifacts. Hash the protected content, not the original
secret-bearing payload, and bind the compliance metadata into that identity.
Refuse uninspectable content without echoing it. Failure reports remain artifact
data, not pipeline control. Return protected verification reporting values only
after evaluating the original receipt against the original pinned contract.
Protect adapter failures at receipt admission, and protect the older verification
path's reporting values only after evaluating its original measurements.

The protected report is not a substitute execution or recovery contract. No
live endpoints, candidate effects, policy permissions or experiments are added.

## Testing Plan

First reproduce failures using real local artifact storage and fake secrets.
Check preparation readback before the candidate callback, final artifact content,
compliance metadata and returned output. Prove redaction cannot mask drift.
Keep storage tests in the product integration suite; measured native runtimes
were 282–539 ms. Pure artifact identity tests remain in the component suite.
Run strict lint, namespace strata checks, both product test contexts, an isolated
packaged suite and the committed-source compliance scan before publication.

## Deployment Plan

Normal package release. Existing immutable artifacts are not rewritten. No live
experiment is needed to validate this producer-side correction.

## Related Issues/PRs

N7 candidate-aware verification, N6 section 7.2, and N3 section 8.

## Checklist

- [x] Regression failures reproduced before implementation.
- [x] Shared policy reused without duplicate detection patterns.
- [x] Original contract and observations remain independent of protected reports.
- [ ] Local gates, owner standards review, CI and full Copilot review pass.
