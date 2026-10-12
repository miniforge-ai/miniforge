<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: wire durable chain lifecycle publication

## Overview

Connect sequential chain lifecycle emission to the typed durable event stream through an explicit runtime option.

## Motivation

The existing executor still emits legacy chain events, despite retaining a distinct invocation and resolved composition.
Current chain events must use that admitted identity and their own chain scope, not the child workflow stream.

## Changes in Detail

- Require durable admission when selecting the current chain event path.
- Refuse a shared retention/journal directory before allocating a run or writing artifacts.
- Keep current chain publication separate from legacy child workflow publication.
- Construct current chain lifecycle drafts from the admitted run and preallocated child identities.
- Preserve acknowledgment failures and the established failure paths.
- Return admitted run identity in current-mode failure diagnostics without treating it as a storage acknowledgment.

## Testing Plan

Verify boundary refusal before storage or execution, immutable identity/scope propagation, separate child streams,
real durable publication, and unchanged legacy behavior.
Run affected consumers serially, verify the packaged CLI, and perform adversarial standards review.

Four affected product consumers passed in 2m24s. After the final orchestration cleanup and diagnostic attribution,
31 focused tests passed with 220 assertions, including real-storage recovery and refusal regressions.
The built CLI jar passed the same 31 tests/220 assertions from an isolated directory with no production source overlay.
The explicit Miniforge standards pack reported no changed-line violations across 4,360 scanned files.

The adversarial pass traced admission → retention → start acknowledgment → child identity allocation →
child execution → terminal acknowledgment. It caught shared storage poisoning journal recovery;
the boundary now refuses that configuration. Fixture construction and lifecycle translation are centralized,
schema namespaces remain declarative, and the real-storage test helpers have their own stratified namespace.

## Deployment Plan

This bounded slice does not claim full chain conformance: binding-edge provenance, execution-state recovery,
and default CLI migration remain dependent work.
No current chain draft may be sent through the legacy publisher, and no unsupported child event may enter the current stream.

## Related Issues/PRs

Base branch: `main`, after merged #2028 and #2027.

## Checklist

- [x] Boundary and lifecycle implementation
- [x] Unit and real-storage integration coverage
- [x] Serial consumers and packaged verification
- [x] Adversarial standards pass and normal signed hooks
- [ ] Exact-head clean review and all CI before merge
