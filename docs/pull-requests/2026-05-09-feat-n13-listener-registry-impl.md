<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(n13): listener registry implementation

## Overview

Implements the persistence primitive for the N13 §2.7 listener
registry — the binding from PR URL → set of agents waiting on the
PR's merge. The schema landed in #808 (`specs/informative/n13-listener-registry.md`);
this PR ships the read/write/transition code that makes it usable.

The registry is a prerequisite for the **Resume Signal Dispatcher**
(separate follow-up PR), which will deliver structured resume
primers to each `:active` listener on `pull_request.closed.merged`.

## Why now

Operator-behavior mining (#808 motivation) ranked manual `merged`
acks as the highest-volume operator turn — ~448 instances across
recent sessions. The dispatcher kills that whole turn class; the
dispatcher needs the registry. This PR is the immediate prerequisite
and unlocks the highest-leverage operator-load reduction in the
N13 backlog.

## Design

### Storage

- Single per-worktree artifact at `<worktree>/.miniforge/listener-registry.edn`
- **Write-rename atomicity**: serialize → write `<path>.tmp` → POSIX-atomic
  `mv` over canonical path. Eliminates partial reads from concurrent readers.
- `.miniforge/` auto-created on first register
- v0 is single-process (no file lock). v1 hardening adds locking only
  if a use case justifies it.

### State model

- v0 stores the **current-state snapshot** (per-entry `:status` field
  per the spec schema).
- The append-only event log called for in spec §Lifecycle is **v1
  hardening** (audit trail, time-travel) — schema already supports it
  via `:status`, so v1 swaps the storage backend without changing the
  public surface.

### Schema validation

- Malli schemas (`ListenerEntry`, `Registry`, `ResumeChannel`) — matches
  codebase conventions (`compliance-scanner.schema`, etc.).
- `validate-entry!` throws on register; `read-registry` enforces
  structural shape on load (catches the common corruption mode where
  `edn/read-string` reads only the first form and silently ignores
  trailing garbage).

### Registration moments

- Spec §Registration moments: registration MUST come from one of
  `:authoring-agent` / `:operator` / `:workflow`. `register!`
  rejects any other `:registered-by` value with a typed error.

## Public API

All re-exported through `pr-lifecycle.interface`:

| API                                | Behavior                                                              |
| ---------------------------------- | --------------------------------------------------------------------- |
| `register-listener!`               | Persist a new entry, return `:listener-id`. Validates registration moment + schema. |
| `unregister-listener!`             | Transition `:active → :cancelled`.                                    |
| `mark-listener-dispatched!`        | Transition `:active → :dispatched`, records `:resume/dispatched-at` + `:resume/dispatch-id`. |
| `cancel-listeners-on-pr-close!`    | Bulk transition every `:active` for a PR URL → `:cancelled`. Used when PR closes without merge. |
| `sweep-expired-listeners!`         | Bulk transition every `:active` past TTL → `:expired`. Idempotent.   |
| `read-listener-registry`           | Load the registry; returns `empty-registry` on missing file.          |
| `listeners-for-pr` / `active-listeners-for-pr` / `listeners-for-agent` | Pure queries. |

State transition table:

```text
register     :→ :active
unregister!  :active → :cancelled
mark-dispatched!         :active → :dispatched
mark-cancelled-on-pr-close! :active → :cancelled (bulk per PR)
sweep-expired!           :active → :expired (TTL-driven, bulk)
```

## Test plan

- [x] `clj-kondo`: clean.
- [x] `listener-registry-test`: 18 tests / 57 assertions pass.
- [x] Coverage:
  - Read on missing / blank / corrupt-EDN files
  - register happy path + bad `:registered-by` rejection + malformed-entry rejection + multiple entries per PR + TTL
    default
  - Each transition (unregister / mark-dispatched / cancel-on-pr-close / sweep-expired)
  - `auto-expirable?` predicate edge cases
  - sweep idempotency
  - Lookup by PR + by agent
  - Write-rename leaves no `.tmp` files
  - `.miniforge/` auto-created
- [x] `bb pre-commit`: ✅ ALL PRE-COMMIT CHECKS PASSED.

## What's NOT in this PR

- **Resume Signal Dispatcher** — uses this registry, lands separately.
- **Listener registration from release phase / authoring agents** —
  currently the registry is only written via direct API calls.
  Wiring registration into the release phase + N11 operator binding
  surface is its own work.
- **Append-only event log storage** — v1 hardening per design note above.
- **File locking** — single-process v0; multi-process needs adding.
- **Multi-tenant store** — Enterprise (per N12); single-tenant artifact
  path is the default.

## References

- Spec: N13 §2.7 + `specs/informative/n13-listener-registry.md` (both from #808).
- #808 — N13 foundations.
- #818 — `pr review --post`.
- #837 — `pr review-monitor`.
- Resume Signal Dispatcher — next PR after this one.
