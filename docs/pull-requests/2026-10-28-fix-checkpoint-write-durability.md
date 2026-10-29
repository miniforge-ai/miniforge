<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Confirm checkpoint bytes before acknowledging publication

## Scope and dependencies

Storage boundary follow-up to the temporary-ownership branch, which depends on #2039.
Do not open this PR until its parent is merged and the branch is aligned.

## Design

Use the existing file-durability interface to force temporary bytes before rename.
Confirm the published file and ancestor directory entries before returning success.
Preserve failure information at the storage boundary; do not claim successful writes
when either durability operation returns an anomaly.

## Verification plan

Test force-before-rename ordering, refusal preservation, confirmation failures,
and actual checkpoint reopen. Run consumers serially and verify the rebuilt package.
Keep signing, standards, exact-head review, and all CI gates.

## Verification so far

The parent CLI fails four new assertions. All three workflow consumers pass
serially (2m15s). The rebuilt CLI is 39,233,857 bytes; isolated checkpoint suites
pass 17 tests and 68 assertions with no production source overlays.
New durability tests take 23–41 ms on the JVM. Two existing store scenarios now
take 139–161 ms and must move to project integration tests before this PR opens.

## Limits

No checkpoint format migration. Multi-file transactional recovery and runner failure
events remain separate integration work. This PR does not claim complete N7 recovery.
