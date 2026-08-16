<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N11 task capsule isolation spec completion (0.2.0 → 0.3.0-draft)

## Overview

Fixes a section-numbering collision, marks an implementation-mapping section
informative, defers secrets and evidence to their owning specs, and adds
Annex A.

## Motivation

**Five duplicate section numbers.** §11's subsections were numbered §10.1
through §10.5, colliding with the TaskExecutor protocol's own §10.1–§10.5.
Both inbound `N11 §10` references — from N11-delta-runtime-adapter — mean the
protocol, so the protocol keeps its numbers and §11's subsections were the ones
misnumbered.

**A normative section pinned to file and line numbers.** §11 maps requirements
to locations like `docker.clj:428–442` inside the normative body, without being
marked informative. Those coordinates rot: the `docker.clj` it cites no longer
exists anywhere in the tree.

**Two contracts restated from their owners.** §8.1 forbade secrets being
"logged or included in evidence records" — that is N3 §8's contract, inherited
by N6 §7.2. §9.1's evidence keys (`:evidence/runtime-class`,
`:evidence/image-digest`, and the rest) are not N6 artifact fields, so the
record cannot be produced conformantly — the same shape as N10 §12.2.

## Changes in Detail

- **§11 renumbered** to §11.1–§11.5 and marked informative, with a note that
  its file and line citations are a snapshot and one of them is already stale.
- **§8.1** defers to N3 §8, keeping the one genuinely capsule-specific rule:
  a secret's *name* and *scope* may be recorded so an auditor can see which
  secrets a capsule was granted without seeing their values.
- **§9.1** gated on registering its keys in N6 §3.1.1, with the interim
  position stated — capsule execution is recorded through evidence N6 already
  defines, correlated by workflow and task identifiers.

## Annex A (informative)

- **Implemented** — `components/dag-executor` provides the TaskExecutor
  protocol with three implementations: worktree, host-guarded, Kubernetes.
- **Specified, not implemented** — no Docker executor exists, so `:docker` is
  unselectable among the runtime classes §5 admits. No capsule evidence record.
  No artifact export gate, so `:export/missing-required-artifact` has no
  producer. No network-policy enforcement.
- **Structural** — secret teardown is stated and unverified. And §9.3's
  prohibition on agents resolving their workspace from
  `System/getProperty "user.dir"` in governed mode is unenforced — that is the
  exact fallback behind the sandbox-leak defect observed in this repository
  during this work, which makes the prohibition load-bearing rather than
  theoretical.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all three changed files.
- Verified no duplicate section numbers remain.
- Verified both inbound `N11 §10` references mean the TaskExecutor protocol
  before renumbering.
- Verified `docker.clj` is absent from the tree and the other cited files
  resolve.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

1. Enforce §9.3 — reject `user.dir` workspace resolution in governed mode.
   This is the one with a demonstrated failure behind it.
2. Implement the artifact export gate (§9.2).
3. Enforce the deny-by-default network policy (§8.2).
4. Register capsule evidence keys in N6 §3.1.1, or drop the record.

## Related Issues/PRs

- Follows the N1–N10 completion passes
- Depends on: N3 §8 (redaction), N6 §3.1.1 (artifact types)
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Spec reviewed against current state before editing
- [x] Duplicate section numbers resolved; inbound references checked first
- [x] Implementation-mapping section marked informative
- [x] Duplicated contracts deferred to their owners (020)
- [x] Annex A marked informative
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] SPEC_INDEX updated
- [x] PR doc created (721)
