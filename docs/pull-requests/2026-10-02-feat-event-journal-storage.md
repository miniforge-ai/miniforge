<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# Persist and recover acknowledged event journals

## Layer and dependency

Infrastructure adapter for the commit primitive in #1982. This dependent branch
starts from `fix/event-commit-sequencing` at `805aaa54`; retarget and refresh from
main after #1982 merges. Do not repeatedly refresh the older evidence PR stack.

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
- Missing, malformed, conflicting, non-contiguous or wrongly identified records fail closed.
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
