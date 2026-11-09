<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): execute one exact native load-stop request

## Overview

Connect canonical stop-request preparation to the existing bounded native HTTP
and synchronous provider-dispatch boundary. Execution remains an explicit trusted
runtime operation, not default activation or new authority.

## Motivation

N7 recovery needs an executable separately authorized stop before checking actual
termination. A patch acknowledgement alone must never become termination evidence.

## Changes in Detail

Reconstruct current retained intent and owned UID and revision, compare the
complete exact proposal, then permit one bounded patch through the required
trusted dispatch callback. Never retry an uncertain outcome. A confirmed receipt
binds name, namespace, UID, concrete revision and the exact one-second deadline;
it establishes neither actual worker termination nor baseline recovery.

The internal class-1 boundary consumes a closed runtime and granted committing
transaction. Representation bounds precede schema traversal; current retained
plan admission and ownership are reread before dispatch. Reuse the existing
owned-future deadline, response-byte limit, strict JSON and no-redirect transport
rather than introducing a second HTTP or callback lifecycle.

Layer: native provider adapter. Base: main; prerequisite #2480 is merged.
Local development initially used its actual signed prerequisite branch; publication
follows prerequisite merge. Eight changed Clojure namespaces each have at most
three inferred strata. Result-map construction and real root issuance
each have one shared fixture/producer, rather than repeated map-shaped authority.

## Testing Plan

Actual retained admission, native creation, root recovery issuance, independent
grant pin and six-argument durable commit are exercised; only HTTP is simulated.
Forward revocation does not revoke the separate recovery grant. Recovery
revocation, a substituted grant pin, changed current intent/identity/revision,
malformed runtime and hash-recomputed noncanonical payloads prevent mutation.
Unconfirmed acknowledgements and timeout retain durable uncertainty; a repeat
commit does not dispatch. Repeated, cross-thread, absent or late-failing callbacks
cannot repeat or fabricate the patch outcome.

- Strict clj-kondo: zero warnings/errors; actual inferred strata at most three.
- Polylith structure, both consuming JVM products and task classpaths pass.
- Fresh CLI build; source and isolated package: 68 tests / 1,538 assertions.
- All curated OPSV/workflow/artifact integration: 234 tests / 3,546 assertions.
- Native compatibility: 308 tests / 1,903 assertions across the existing 42 suites.
- Five injected faults cause assertion failures, with zero test errors.
  They cover runtime closure, canonical reconstruction, acknowledged identity,
  acknowledged shape and synchronous provider ownership.

The initial fixture incorrectly passed a grant/transaction directory as breach
history; the real issuer refused it. Breach history, recovery grants and effect
transactions now use distinct stores. No issuer or authority check was weakened.

## Deployment Plan

No live experiments, cluster mutation, traffic, image pulls or credentials are
authorized by this implementation work. Current effect admission, separate exact
root grants and six-argument pinned commit remain host obligations.
This slice is not an N4 effect-admission or complete native-runner proof.

## Related Issues/PRs

N7 §5.5; N10 §1.5; #2475; #2478; #2479; #2480.

## Checklist

- [x] Prerequisite exact stop-request constructor merged.
- [x] Implement, validate and perform owner adversarial plus pinned-rule review.
- [ ] Normal signed commits, budgets, current full review, CI and guarded merge.
