# fix: version paired checkpoint storage identity without breaking legacy receipts

## Overview

Foundation for trustworthy checkpoint retention across aliased storage paths.
The product owner approved optional paired root/run location metadata on
2026-10-31. N3 §3.21 owns the wire contract; this is not a new event family.

## Motivation

The physical run directory need not have the logical Workflow UUID as its name.
Generation files and phase-directory aliases also make parent inference unsafe.
The recently merged root-only field cannot identify every physical run directory.

## Changes in Detail

- Payload 1.1.0 supports the optional paired `:checkpoint/location` record.
- Both five-argument constructors and deployed sixth-argument root strings retain
  payload 1.0.0. Existing journal records remain admissible and replayable.
- A public record constructor centralizes the shape; redaction refuses to
  silently rewrite storage identity. Other recovery events remain at 1.0.0.
- N3 and the specification index record the approved decision and compatibility.

## Testing Plan

Focused admission, duplicate/reopen, compatibility and sensitive-locator tests:
5 tests, 81 assertions passed, including all four unchanged recovery profiles.
Event-stream tests passed across all four consuming projects (1m18s). Changed
Clojure files have zero lint warnings/errors and at most three actual strata.
Compliance scan and normal hooks are required before push.

## Deployment Plan

No automatic migration of retained journals. This independently mergeable
foundation precedes workflow producer and retention integration in a follow-up.

## Related Issues/PRs

Follows #2094 and #2280; preserves their deployed root-string form.
PRs #2287 and #2288 already provide generation durability and run-lock coverage; no duplicate
implementation of those fixes is included.

## Checklist

- [x] Product-owner approval recorded
- [ ] Local verification and adversarial standards review
- [ ] Fresh Copilot review and all CI gates
