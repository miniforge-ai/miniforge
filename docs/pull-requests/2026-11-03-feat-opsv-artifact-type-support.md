<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Admit the required OPSV artifact types

## Overview

Support the four N6 §3.1.1 OPSV artifact type identifiers in the shared artifact
schema. Keep the existing artifact vocabulary and reject unknown types.

## Motivation

N7 contract retention and reports need canonical artifact tags. The current
shared schema only admits the older generic tags, preventing canonical OPSV
artifacts from passing immutable publication and snapshot validation.

## Changes in Detail

- Add Experiment Pack, Operational Policy Proposal, verification-report, and
  metric-snapshot artifact tags required by N6.
- Keep existing tags and all other artifact fields unchanged.
- Test the public artifact validator with actual producer-shaped records.
- Consolidate artifact fixtures and remove duplicate throwing-validator tests;
  the existing dedicated compatibility suite retains that API coverage.

Ownership: `schema`, the shared vocabulary owner. No new dependencies or
production namespaces. This admits data types, not execution authority.

## Testing Plan

Test all four required tags, preserve existing tags, and reject unknown values.
Validate affected product contexts and native packaging, normal hooks, CI,
and full-head review. Trace existing type consumers before publication.

## Deployment Plan

Additive schema support only. Updating phase material emission and completing
N6 artifact provenance remain separate implementation work.

## Related Issues/PRs

Base branch: `main`. Implements the existing N6 §3.1.1 requirement for N7.
No normative amendment or new execution capability is introduced.

## Checklist

- [ ] Pin accepted and rejected type behavior through the public interface.
- [ ] Trace affected consumers and complete owner standards/adversarial review.
- [ ] Pass all product, native, hook, CI, and full-review gates.
