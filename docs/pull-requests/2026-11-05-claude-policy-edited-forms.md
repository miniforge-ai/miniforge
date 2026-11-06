<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(gate): block on judge findings in the forms a change edits, not a touched file's older debt

## Overview

`:policy-verify` and `:policy-review` now block only on LLM-judge findings
inside the top-level forms a change edits. Findings elsewhere in a touched
file are recorded as gate warnings and do not block. A finding the gate cannot
place still blocks. The spec is `work/done/policy-judge-edited-forms.spec.edn`.

## Motivation

The judge reads each changed file whole, and every acting finding (`:hard-halt`
or `:require-approval`) blocked. A run that touched an existing file therefore
had to clear every finding in it, including forms the change never edited. On
2026-11-05 the judge reported acting findings in each of ten files sampled from
main, including `policy_pack.clj` and `no_secrets.clj`. That day a dogfood run
edited one function in `runner_environment.clj`, was blocked by findings
elsewhere in the file, and spread into callers and tests with findings of
their own until it failed. `:no-secrets` already reports only matches that
touch added lines.

## Changes in Detail

1. `ai.miniforge.gate.edited-forms` (new): `form-spans` locates each top-level
   form with edamame, which reads past auto-resolved keywords with unknown
   aliases, reader conditionals and unknown tagged literals; content it cannot
   read, or that holds an invalid literal such as a duplicate map key, is an
   `:invalid-input` anomaly. A first read keeps values (with tags and
   auto-resolved aliases distinct) to catch invalid literals; a second reads
   locations. `edited-lines` widens a change to whole top-level forms in
   `.clj`, `.cljc`, `.cljs`, `.bb` and `.edn` files: a form is edited when it
   holds an added line or a pure removal, or sits directly below removed
   lines that start with `^` or `#_` (a removed reader prefix changes it). In
   other files the added lines are the edit.
2. `ai.miniforge.gate.policy-pack.scope` (new): `scope-violations` diffs each
   changed file against the task base (the artifact's `:code/base-refs`, the
   context's `:execution/task-base-refs`, then the environment base SHA, never
   HEAD) and splits each judge entry. Findings outside the edited lines move to
   a copy of the entry whose rule action is `:warn` and whose message names the
   sites; the rest keep the rule's action. It runs git only when the judge
   reported something.
3. `policy-pack.evaluation/compiled-check-result` passes its violation entries
   through `scope-violations` before classifying them.
4. `git-change/removals` (new) reads each hunk of a zero-context diff that
   removes lines: where it falls in the new version, whether it replaced
   lines, and the removed text.
5. A finding blocks as before when it has no positive line or a line past the
   file's last, or its file has no checkout or base to diff against, git
   fails, or the content does not parse.
   A new file has every line added, so all its findings block.
6. The gate's namespace docstring describes the change scope, and drops two
   references to earlier PRs.
7. `components/gate/deps.edn` adds `borkdude/edamame` 1.6.42 (its runtime
   dependency is `tools.reader` 1.5.2, already used by `compliance-scanner`;
   Babashka ships edamame built in) and `ai.miniforge/anomaly`.

Rule evidence keeps the N3 statuses: a rule whose findings are all outside the
change is still `:failed` ("violation found"), and its summary message says the
findings are outside the change. Gate warnings reach the checkpoint as
`:phase/gate-warnings` and are not sent back to the implementer.

## Testing Plan

1. `policy_pack_scope_test.clj` runs the verify gate over a real temp git repo
   with a stub judge and covers each acceptance criterion: a finding in an
   unedited form warns; one in the edited form blocks, and the error names only
   that finding; an added line between forms blocks; a deletion inside a form
   makes that form edited; a new file blocks on any line; line 0, no line, no
   worktree and unparseable content all block.
2. `edited_forms_test.clj` locates forms in a namespace with `#?(:clj ...)`,
   `::alias/kw` for an unknown alias, `#inst` and an unknown tag, and checks
   form widening, deletion points and non-Clojure files.
3. All 22 gate test namespaces pass on the JVM (231 tests, 870 assertions); the
   new tests and `no_secrets_test` pass under Babashka on the `bb miniforge`
   task classpath.
4. clj-kondo and stratum-lint report nothing on the changed files.

## Deployment Plan

No deployment step. Runs that touch files with older findings stop failing on
them; those findings appear in the gate's warnings instead.

## Related Issues/PRs

1. Builds on #2410 (the `policy_pack.clj` namespace split) and #2411 (the
   shared `git-change` helpers), both merged.

## Checklist

- [x] Every acceptance criterion in the spec has a test
- [x] Gate tests pass on the JVM; new tests pass under Babashka
- [x] clj-kondo and stratum-lint clean
