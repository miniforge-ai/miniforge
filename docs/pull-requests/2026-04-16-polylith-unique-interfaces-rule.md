<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Standards: components MUST NOT overload Polylith interface names

## Context

`poly check` reports Error 106 (multi-impl interfaces) in the miniforge
workspace: `data-foundry` is claimed by 22 components in development, and
`phase` is claimed by three. An earlier draft of the remediation spec
suggested fixing this with Polylith **profiles** — but that is the wrong
tool. Profiles bundle dev/test variants; they do not resolve shared
interface names. Reviewing ixi's real-world patterns (`tool-pubsub` /
`adapter-pubsub`) and miniforge's own LLM protocols confirms: **no two
components ever share a `:poly/interface` name.** Runtime swapping happens
by multimethod dispatch or adapter selection, not by overload.

This insight needs to live in the standards so future agents don't
re-invent the profile-as-workaround pattern.

## What changed

### `.standards/frameworks/polylith.mdc`

Adds a new **Unique Interface Names (CRITICAL)** section describing three
valid patterns for "multiple implementations":

1. **Multimethod dispatch inside a canonical interface component** — one
   component owns the canonical interface and a `(defmulti ... dispatch-fn)`;
   backend-provider components each expose their OWN distinct interface
   and register methods. Modeled on ixi's `tool-pubsub/extensibility.clj`.
2. **Adapter components with distinct interfaces** — each adapter exposes
   its own interface; swap by changing which adapter the project includes.
3. **Extension, not replacement** — specialization components
   (e.g., `phase-software-factory`) get their OWN interface and depend on
   the canonical base (`:phase`) rather than claiming to re-implement it.

Explicit invalid example: `phase`, `phase-deployment`, and
`phase-software-factory` all declaring `:poly/interface :phase`. Adding a
profile hides the conflict — the fix is to rename interfaces.

### `components/phase/resources/packs/miniforge-standards.pack.edn`

Recompiled from the updated .mdc via `bb standards:pack`. Timestamp
refresh + the new rule content land in the pack so runtime rule injection
picks up the guidance automatically.

### `work/polylith-compliance-remediation.spec.edn`

Group 2 rewritten: replaces the profile-based description with the
unique-interface + multimethod-dispatch approach, citing ixi's
`tool-pubsub` / `adapter-pubsub` / LLM-protocols patterns by name. Adds
an acceptance criterion:

> "No two components share an interface name anywhere in the workspace."

## Verification

- `bb standards:pack` regenerates the pack without errors (23 rules,
  0 failed).
- `grep -c "Unique Interface Names" components/phase/resources/packs/miniforge-standards.pack.edn` → 1.
- Pre-commit hooks (lint + changed-brick tests for `phase`) all green.

## Follow-up

- Actual remediation of the `data-foundry` (22 components) and `phase`
  (3 components) interface-name collisions happens in the spec itself —
  this PR only captures the rule. Running the spec will rename interfaces
  per the three patterns above.
- The `.standards/` submodule is now redundant (knowledge store reads
  packs, not `.mdc` directly). Removal will ship separately once the
  submodule is confirmed unreferenced by any runtime code path.
