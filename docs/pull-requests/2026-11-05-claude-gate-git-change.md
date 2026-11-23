<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(gate): move the no-secrets git helpers into a shared namespace

## Overview

The `:no-secrets` gate's helpers for running git, resolving the task base,
and reading a zero-context diff move into `ai.miniforge.gate.git-change`.
`no_secrets.clj` calls them there. The gate's behaviour does not change.

## Motivation

The policy-pack gate is about to scope LLM-judge findings to the lines a
change edits (`work/policy-judge-edited-forms.spec.edn`). It needs the same
base resolution and diff reading `:no-secrets` already does. Moving the
helpers first lets both gates use one copy.

## Changes in Detail

1. `components/gate/src/ai/miniforge/gate/git_change.clj` (new) holds `run`,
   `git-argv`, `added-lines`, `scratch-dir`, `base-candidates` and
   `resolve-base`, moved from `no_secrets.clj`, plus `version-diff`, the
   artifact-versus-base-blob diff that `artifact-version-findings` did
   inline.
2. Two helpers change shape so other callers can use them:
   1. `scratch-dir` takes the directory-name prefix; `no-secrets` passes
      `"mf-no-secrets-"`, the prefix it used before.
   2. `base-candidates` no longer ends with `"HEAD"`. Once the implement
      phase commits, HEAD holds the change itself, so a gate that measures
      only the diff must not fall back to it. `no-secrets` appends `"HEAD"`
      where it resolves the base, so its candidate order is unchanged.
3. `no_secrets.clj` requires `git-change` (`run` and `git-argv` referred, so
   their call sites are unchanged) and drops its copies. Its namespace
   docstring points at `git-change` for the argv defaults and where git
   runs. Its temp-file prefix, written out twice before, is now the named
   constant `scratch-prefix`.
4. The `added-lines` and `scratch-dir` tests move from `no_secrets_test.clj`
   to the new `git_change_test.clj`, which also covers `base-candidates`
   and `version-diff` for a path with no base blob.

## Testing Plan

1. `git_change_test`, `no_secrets_test` and `interface_test` pass on the JVM:
   54 tests, 114 assertions.
2. clj-kondo and stratum-lint report nothing on the changed files.

## Deployment Plan

No deployment step; the `:no-secrets` gate's behaviour is unchanged.

## Related Issues/PRs

1. Used next by the change-scoped judge findings PR
   (`work/policy-judge-edited-forms.spec.edn`).
2. Independent of #2410 (the `policy_pack.clj` namespace split).

## Checklist

- [x] `:no-secrets` tests pass unchanged apart from the two moved tests
- [x] clj-kondo and stratum-lint clean
