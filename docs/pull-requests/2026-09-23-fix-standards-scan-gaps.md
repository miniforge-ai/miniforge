<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: close confirmed repository standards scan gaps

## Overview

Review the full-repository standards findings found during N7 implementation.
Fix confirmed gaps without treating scanner examples or programmer guards as
ordinary runtime failures.

## Motivation

The implementation completion work requires an adversarial standards pass.
The baseline scanner reports 29 findings: 17 missing document headers, two map
default patterns, and ten exceptions needing human review.

## Changes in Detail

- Add the required copyright header to historical PR documents.
- Split legacy long sentences and fix list formatting exposed by the prose hook.
  Preserve historical technical claims, examples, links and test results.
- Remove a redundant empty-map fallback when merging persisted cursors.
- Record the disposition of findings that are not safe mechanical fixes.

## Testing Plan

Run cursor-store tests, the full standards scanner, and all pre-commit checks.
Inspect each changed document and ensure no substantive history is rewritten.

Results: Polylith check and 11 cursor-store tests / 48 assertions pass in the
Data Foundry project. Existing tests cover first-run empty maps, corrupt files,
round trips and preservation of quiet stages. The scanner drops from 29 findings
to 11; this is not a claim of repository-wide standards closure.

### Remaining scan findings

- The map-default match in `compliance-scanner/execute.clj` is an example string
  in its REPL comment. It demonstrates the invalid form the fixer consumes.
  Replacing it would damage the example, not fix executable code.
- Eight exception findings guard serialization or authored catalog contracts:
  zettel timestamps, bingo entries/cards, canonical content hashes, canonical
  pack EDN, and pack timestamps. Converting them mechanically could serialize
  an anomaly as successful content or change existing public result contracts.
- The workbench registry exporter is a task entry point; its documented failure
  contract and tests require exceptions for invalid bundled fixtures.
- Trust-root configuration rejects invalid operator entries by throwing. This
  needs a separate boundary/result-contract migration, not suppression or a
  fallback that silently removes publishers. It remains open.

No scanner rule or finding is suppressed by this PR. All staged documents pass
plainspeak without exemptions.

## Deployment Plan

No configuration change, external effects, or migration.

## Related Issues/PRs

Companion standards cleanup for the N7 implementation-completion work.

## Checklist

- [x] Component tests and scoped adversarial standards review pass.
- [ ] CI passes and review settles.
