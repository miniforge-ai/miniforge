<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: redact secrets at event construction per N3 §8.1

Historical record. Merged as PR #1842.

## Overview

Adds the `ai.miniforge.redaction` component and wires it into
`event-stream/core/publish!`, so a secret-bearing value never reaches a
sink, the in-memory log, or a subscriber.

## Motivation

Four specs recorded this contract as unimplemented in their annexes — N3,
N6, N8 and N9 — and `REDACTED` appeared nowhere in the tree. The only
redaction that existed was `dag-executor/host_git_guard/redact-credentials`,
which strips userinfo from git remote URLs: real, but scoped to one field of
one subsystem.

N3 §8.1 had required this since 0.9.0. The requirement is a MUST NOT on
**emission**, not a filter on delivery:

> A redacting sink does not make a secret-bearing event conformant — the
> event is already in memory, already sequenced, and already durable per
> §4.3. Redact at construction.

## Changes in Detail

**Wired at `core/publish!`**, not `interface.clj`, because `heartbeat.clj`
calls `core/publish!` directly; interface-level wiring would have left
heartbeat events unredacted. `publish!` is also the last point at which no
durable or delivered copy exists.

**Two detection mechanisms**, because secrets hide in two places:

- *by key name* — `password`, `secret`, `token`, `api-key`. A password's
  value is unremarkable; only its key identifies it.
- *by value shape* — `AKIA…`, `sk-…`, `gh[pousr]_…`, PEM blocks, JWTs,
  connection strings with inline credentials, `Bearer`/`Basic` headers.

Patterns live in `resources/config/redaction/patterns.edn` (dewey 007) as
strings compiled with `re-pattern` — EDN has no regex literal, and N8 §5.2
forbids a function as a configuration value.

Excluded values are replaced with `"[REDACTED]"` rather than dropped, per
§8.2: an absent key is indistinguishable from a key never set.

**Narrowed twice after CI caught a regression.** The `token` pattern matched
`{:metrics {:tokens 42}}` and replaced an LLM token count with a string,
breaking the web dashboard with a ClassCastException. Values that cannot
carry a secret (number, boolean, nil, inst, uuid) are exempt from the key
rule, and keys ending in a qualifier (`-id`, `-endpoint`, `-scope`) name a
*reference to* a secret rather than the secret. The second is safe only
because it disables the key rule alone — the value is still shape-scanned.

## Testing Plan

Assertions cover the returned event, the in-memory log, and the subscriber;
the return value alone would look correct under a sink-level fix. Full
event-stream suite: 357 tests / 1894 assertions.

## Performance Impact

~65us/KB, linear in payload size. Combining the value patterns into one
alternation measured ~45% *slower* — Java applies a literal-prefix
optimisation per pattern and loses it once combined. Left sequential.

## Related Issues/PRs

- PR #1843 — `collector.clj` split (prerequisite for the bundle half)
- PR #1844 — bundle-side redaction (N6.SD.4)
- PR #1845 — leaks in keys, namespaces, and metadata

## Checklist

- [x] Wired where no durable copy exists yet
- [x] Patterns as config, not code
- [x] Regression from over-matching narrowed and tested
