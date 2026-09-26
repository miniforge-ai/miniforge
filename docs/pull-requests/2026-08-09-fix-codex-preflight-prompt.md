<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): deliver the backend preflight prompt the way the backend expects

## Overview

The CLI's backend preflight probe built a codex command whose argv ended
in `-`, the stdin-prompt placeholder. It then ran with stdin closed immediately.
Codex therefore received an empty prompt
on every preflight, and the probe's verdict said nothing about whether
the backend could answer.

## Motivation

`components/llm` moved codex to stdin prompt delivery in #1024
(`:prompt-via :stdin` in `llm/backends.edn`, `codex-args` defaulting to
`:stdin` and appending `-`). Real execution handles both halves of that contract.
`resolve-prompt-via` reads the declared mode and passes it into `:args-fn`
to shape argv. Execution pipes the prompt through the exec layer's `:stdin` option.

The CLI preflight probe reads the same `llm/backends` map and calls the
same `:args-fn`, but did neither. It passed no `:prompt-via`, so
`codex-args` defaulted to `:stdin` and dropped the prompt from argv.
Then `process/run-cli-command` closed the child's stdin before it could read anything.

`ai.miniforge.cli.workflow-runner.preflight-test/run-backend-preflight-exercises-generic-cli-success-path-test`
failed on main asserting the prompt was the last argv element. That
assertion was stale, but the reason it was stale is a production defect,
not a test-only drift.

## Changes in Detail

### `components/llm`

New `llm/backend-prompt-via` exposes the backend's declared prompt-delivery mode.
Callers need not restate the `:argv` fallback. The impl retains its private
`resolve-prompt-via`; a new test checks agreement across every backend and an
undeclared map.

The accessor is a separate defn. Re-exporting the private var would require
editing `protocols/impl/llm_client.clj`, which already fails the stratum gate:
11 layers against a maximum of 3. This SL003 also reproduces on untouched
`origin/main`. Splitting that namespace is a separate change.

### `bases/cli`

- `process/run-cli-command` accepts a `:stdin` option. The string is written
  to the child's stdin on a background thread, then the stream closes.
  A nil/empty value closes it without writing, preserving argv-backend behavior.
  Off-thread so a child that never drains stdin cannot block
  the caller past the command timeout.
- `preflight-probe/generic-preflight-command` becomes
  `generic-preflight-invocation`, returning `{:args :stdin}`. It threads the
  backend's declared `:prompt-via` into the `:args-fn` request and returns
  the prompt as stdin when the backend expects it there.

The Claude probe is unchanged: it does not go through `:args-fn`, and its
direct `-p <prompt>` form is a valid claude CLI invocation.

## Testing Plan

- `run-backend-preflight-exercises-generic-cli-success-path-test` pins the codex
  contract. Argv ends in `-` without prompt text; the probe pipes the prompt via `:stdin`.
- New `run-backend-preflight-keeps-argv-backend-prompt-in-argv-test` covers
  the `:prompt-via :argv` side (opencode): prompt in argv, no stdin.
- New `run-cli-command-pipes-stdin-to-the-child-test` and
  `run-cli-command-closes-stdin-without-input-test` exercise the subprocess
  layer against a real `cat`.
- New `backend-prompt-via-test` and `backend-prompt-via-matches-impl-test`
  in the llm interface tests pin the accessor, its `:argv` fallback, and its
  agreement with the impl's resolver.

Run:

```zsh
clojure -M:dev:test -e "(require 'clojure.test 'ai.miniforge.cli.workflow-runner.preflight-test) (clojure.test/run-tests 'ai.miniforge.cli.workflow-runner.preflight-test)"
clojure -M:dev:test -e "(require 'clojure.test 'ai.miniforge.llm.interface-test 'ai.miniforge.llm.args-fn-test) (clojure.test/run-tests 'ai.miniforge.llm.interface-test 'ai.miniforge.llm.args-fn-test)"
```

Both green: 15 tests / 49 assertions and 90 tests / 368 assertions, 0
failures, 0 errors.

## Deployment Plan

No migration or configuration change. The preflight probe starts sending a
real prompt to codex. An install that silently "passed" on an empty prompt may
now fail preflight, as intended by the fail-closed behavior.

## Related Issues/PRs

PR #1024 `fix(llm): send codex prompts via stdin` introduced the contract
the preflight probe did not follow.

## Checklist

- [x] Preflight namespace tests pass
- [x] llm interface + args-fn tests pass
- [x] No other caller of `llm/backends` `:args-fn` outside the llm brick
- [x] Apache 2.0 headers unchanged on all touched sources
