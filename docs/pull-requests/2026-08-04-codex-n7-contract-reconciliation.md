<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# spec: reconcile N7 operational policy contracts

## Overview

Makes N7 a current, internally consistent implementation contract before OPSV
code is introduced. It aligns the extension with the live N1/N3/N6 wire
contracts and the later N8/N10 governance model.

## Motivation

The original N7 predates governed effect grants, emergency-stop semantics, and
the repository's current extension catalog. It also cites the wrong N3 section,
uses event names that differ from N3, and treats `APPLY_ALLOWED` as both intent
and authority. Implementing those contradictions would produce unsafe or
nonconforming behavior.

## Layer

Foundations — normative contracts only.

## Depends on

- [miniforge#1638](https://github.com/miniforge-ai/miniforge/pull/1638) — merged

## Changes in Detail

- Reconcile N7 phases, event names, evidence correlation, and cross-references.
- Define direct apply as a requested intent that still requires N10 execution
  authority and transactional rollback behavior.
- Integrate N8 emergency stop and safe-mode behavior.
- Make failed verification a hard block for direct mutation.
- Repair the specification catalog and README extension guidance.

## Test plan

- Validate every N7 cross-reference and duplicated wire contract against its
  owning core spec.
- Run Markdown formatting and the full pre-commit gate.
- Adversarially trace recommend-only, PR-only, direct-apply, verification
  failure, guardrail abort, and emergency-stop paths.

## Deployment Plan

Merge before replacing the stale N7 work specs or implementing OPSV components.

## Related Issues/PRs

- [miniforge-ai/miniforge-standards#96](https://github.com/miniforge-ai/miniforge-standards/pull/96)

## Checklist

- [ ] Core and extension contracts agree
- [ ] Unsafe override paths are eliminated
- [ ] Specification catalog is complete
- [ ] Repository checks pass
