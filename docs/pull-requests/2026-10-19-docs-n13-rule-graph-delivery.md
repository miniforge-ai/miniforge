<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs(spec): N13 delta for rule-graph policy delivery

## Overview

Add `specs/normative/N13-delta-rule-graph-delivery.md`, a draft amendment to N13, and index it. The delta replaces
frontmatter-keyed prompt injection with a deterministic query over a compiled rule graph. The query key is phase,
task type and changeset. Spec only; no code changes.

## Motivation

The owner's review of miniforge-standards#110 asked why rule delivery is context injection rather than a query for
related compiled rulesets. Reading and measuring the current paths found these gaps:

1. 31 rules reach neither the reviewer nor a gate check. For 28 of them the cause is the same: review passes no
   artifact path, so every file glob fails.
2. `alwaysApply` sets default enforcement action and severity. Standards#110 therefore downgrades `:std/clojure`
   at the next re-pin.
3. Delivered rule text carries no rule ids. Review findings cannot be attributed to a rule.
4. Release receives about 36.6k tokens, mostly rule bodies. Review receives about 44.4k.
5. Rules cross-reference each other (about 97 references), and nothing reads them.

## Layer

Specification. Base branch: `main` at `a16f06778`. No prerequisite PRs.

## Changes in Detail

1. New delta. §2 records current paths with measurements and is informative. §4 to §8 define the graph, queries,
   delivery, enforcement invariants and IDE path. §10 stages the work, and §13 lists `N13D.RG.*` requirements.
2. `SPEC_INDEX.md`: applicability row, amendment table row, N13 summary line, version 0.26.0-draft.

## Testing Plan

Run plainspeak and markdownlint on every touched file. Recompute the §2 numbers with the scripts described in the
delta's Annex A. An adversarial review checked §2 claims against the code before push.

## Deployment Plan

Documentation only. No runtime behavior or data migration changes.

## Related Issues/PRs

1. miniforge-standards#110 (the trigger).
2. `work/knowledge-mcp-query.spec.edn` (the `lookup_policy` MCP tool plan this delta supersedes).

## Checklist

- [x] Spec only; no implementation.
- [x] Delta names its base and is indexed.
- [x] Pass prose and Markdown checks.
- Require clean review and all CI checks before merge.
