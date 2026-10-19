<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Construct current workflow terminal facts

## Overview and motivation

Completion and failure constructors need current-journal drafts before the workflow
runner can require durable terminal acknowledgments. Share payload projection with
legacy constructors to preserve diagnostics without duplicated terminal maps.

## Layer and dependencies

Event-stream foundation, based on main. Independent of the start/phase constructor
slice #2047. No default host switch or workflow runner control-flow change.

## Changes in detail

- Extract shared completion metrics and failure diagnostic projections.
- Preserve legacy terminal payloads and sequence reservations.
- Build sequence-free terminal drafts for current journals.
- Refuse contradictory completion status; require actual duration, evidence and
  canonical failure class at current publication admission.
- Propagate identity-allocation failures without decorating them as events.
- Preserve canonical anomaly diagnostics and retryability. Message-less exceptions
  use their classified diagnostic instead of a nil failure reason on both profiles.

## Testing plan

Check current and legacy constructors, error projection, missing facts, publication
and durable replay. Run consumer suites, lint, strata, composition and the pinned
standards scan serially; rebuild and test the isolated CLI package.

All three product event-stream suites passed (2m30s). The isolated CLI package
passed 14 tests / 221 assertions. Lint and inferred strata have zero findings.
The standards audit runs after committing: incremental scans compare committed
revisions, and their file count describes the repository index, not audited files.

## Standards audit correction

A post-commit audit of recent related PRs found missing documentation headers or
omitted author/email lines, with no runtime-code findings. This PR repairs the
merged headers. The open constructor PR carries its own header fix and fresh review.

## Deployment plan

Follow-up runner integration supplies authoritative terminal facts and checks
publication receipts. This change does not itself establish runtime fail-closed
behavior or complete the default host migration.

## Checklist

- [ ] Adversarial standards and compatibility review
- [ ] Serial source and package verification
- [ ] Signed bounded commits, settled review and all checks
