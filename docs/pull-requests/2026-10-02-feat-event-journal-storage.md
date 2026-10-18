<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Persist and recover acknowledged event journals

## Layer and dependency

Infrastructure adapter for the commit primitive in #1982 (merged as `d4318280`).
This dependent branch started from `fix/event-commit-sequencing` at `805aaa54`;
refresh onto merged main before opening this PR. The older evidence stack remains
unchanged while this prerequisite settles.

## Scope

Reuse immutable artifact publication for bounded, checksummed, lossless event
records. A dedicated journal directory has one exclusive owner. Recover and
validate stored scope positions before accepting new publication. Unknown write
outcomes require reopening/recovery, not counter reuse or record renumbering.
The live publisher migration is the next PR; this adapter alone does not close
the failing OPSV recovery acceptance or claim complete N3 scope/retention support.

## Acceptance matrix

- Fresh publication and reopen preserve exact event values and continue sequences.
- Retrying a committed identity returns its original acknowledgment without duplication.
- Concurrent owners of one directory are refused; closing releases ownership.
- Unreadable, malformed, conflicting, non-contiguous or wrongly identified records fail closed.
- Uncertain writes remain fenced until recovery confirms the actual durable records.
- Partial temporary files are not mistaken for committed records.
- Unsafe paths, symlinks and unavailable durability barriers are refused.
- Closed stores cannot write; interruption and fatal errors retain their semantics.

## Standards and validation

Use component interfaces, shared constructors and genuine bounded namespace strata.
Avoid a second serializer or duplicate atomic-file machinery. Complete the focused
matrix and adversarial trace before broad serial consumer/build verification and
one reviewable push. Normal hooks, size limits, current-head review and all CI
remain merge requirements. No deployment or live provider action is included.

## Verification and adversarial pass

- Structural recovery and real-file tests: 6 tests / 36 assertions, JVM pass.
- Ownership, interruption and close-race tests: 3 / 25, JVM pass.
- Artifact and event-stream brick tests pass in all four project compositions.
- CLI rebuild passes; rebuilt packaged acceptance passes 31 / 162.
- Root standards scan: 4219 files, zero violations. Kondo and strata pass.
- Normal signed hooks pass on the inventory and recovery commits; final adapter
  and lifecycle-test commits use the same gates, without overrides.

Traced fresh open, two scopes, retry, close, reopen and next acknowledgment;
counter reconstruction happens only after complete record validation and
durability reconfirmation. Unknown write outcomes fence the live journal.
Separate-process probes verify exclusion survives a refused same-runtime open
and a stale handle's repeated close. Closing waits for in-flight acknowledgment.
Symlink lock files are refused without changing their targets; failed and fatal
recovery release ownership. Test fixtures share constructors and lifecycle ports.

The first packaged run exposed an unsupported Babashka `LinkageError` constructor
in a fixture. Using supported `Error` retains the fatal-propagation assertion;
the final packaged and affected JVM reruns pass. Production code was unchanged.

## Explicit limits

This is an internal adapter, not a second event publication API. The publisher
must validate event families, select authoritative scopes and redact drafts before
calling it. Recovery checks storage structure, identity and contiguous positions;
it does not derive scope authority from stored content. The host owns the directory
and ancestors; advisory locks do not authenticate writers. Deletion/retention is
not implemented: gaps are rejected, but removal of a whole journal or its final
suffix cannot be detected without an external checkpoint/sealed-range contract.
Default publisher wiring, retention, replay APIs and sealed-range holds remain
separate work. This PR does not claim the OPSV regression or N3 is complete.
