<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs(specs): close N4 Annex A's signature items

## Overview

Annex A of N4 still reports pack signature verification as self-certifying and
the canonical serialization as partial. Both were closed by
[#1669](https://github.com/miniforge-ai/miniforge/pull/1669). This updates the
annex to match, and names what remains.

Informative annex only. No normative change.

## Motivation

PR #1658 introduced Annex A while #1669 fixed what A.4 described.
Both were open at once. #1658 landed first, so the annex reached `main`
describing a vulnerability fixed hours later. Readers use the annex to assess
implementation trust. Reporting a closed security gap as open misleads them.

## Changes in Detail

- **A.4** — marks self-certifying verification closed. Identifiers now resolve
  against a configured trust-root store, shipped empty: an unconfigured
  deployment trusts nobody. It records the two adjacent defects closed with it.
  Remaining gaps include §8.2 steps 4 and 5. The shipped `:trust/*` rules bind
  to `:semantic` (LLM-as-judge), not a `:custom-fn` over the verifier. They run;
  they just never call `verify-signature`, which has no caller outside its
  component.
- **A.4, second entry** — a divergence the review surfaced: §5.1.8 names the
  pack-trust rules `require-signature-verification` /
  `enforce-publisher-allowlist` / `enforce-minimum-trust-level`, while the
  shipped pack declares `:trust/require-signature` /
  `:trust/publisher-allowlist` / `:trust/minimum-trust-level`. §1.1 makes rule
  IDs the durable anchor, so the two should agree.
- **A.3** — canonicalization rewritten for what `policy-pack/canonical-edn`
  and `canonical-order` now do, replacing the "partial on determinism" note.
- **Annex preamble** — closed rows remain and name the closing change.
  The annex records historical gaps as well as remaining ones.
  Date moved to 2026-08-06.
- **Version history** — one entry for this annex-only revision.

A first draft of A.4 said the `:trust/*` rules "do not run" because they carry
no `:custom-fn`. Review corrected it: `compiler/resolve-detector` binds a
`:custom` rule with no `:custom-fn` to `:semantic`, so they run under the
LLM judge. The gap is narrower and more specific than "unwired" — a model
judging whether a pack looks signed is not a signature check.

## Testing Plan

Two markdown files, no code: the N4 annex and this write-up. `bb commit-budget`
and the pre-commit gate cover them; CI runs the workspace checks.

## Deployment Plan

None — informative spec text.

## Related Issues/PRs

- [#1669](https://github.com/miniforge-ai/miniforge/pull/1669) — the implementation change this records
- [#1658](https://github.com/miniforge-ai/miniforge/pull/1658) — N4 0.7.0-draft, which introduced Annex A

## Checklist

- [x] A.4 reads as closed and names what remains
- [x] A.3 describes the current implementation
- [x] No normative text touched
- [x] Version history entry
