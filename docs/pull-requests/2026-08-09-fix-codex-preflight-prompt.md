# fix(cli): deliver the backend preflight prompt the way the backend expects

## Overview

The CLI's backend preflight probe built a codex command whose argv ended
in `-` — codex's "prompt arrives on stdin" placeholder — and then ran it
with stdin closed immediately. Codex therefore received an empty prompt
on every preflight, and the probe's verdict said nothing about whether
the backend could actually answer.

## Motivation

`components/llm` moved codex to stdin prompt delivery in #1024
(`:prompt-via :stdin` in `llm/backends.edn`, `codex-args` defaulting to
`:stdin` and appending `-`). Real execution in the llm brick handles both
halves of that contract: `resolve-prompt-via` reads the backend's declared
mode, passes it into `:args-fn` so argv is shaped for it, and pipes the
prompt through the exec layer's `:stdin` option.

The CLI preflight probe reads the same `llm/backends` map and calls the
same `:args-fn`, but did neither. It passed no `:prompt-via`, so
`codex-args` fell through to its `:stdin` default and dropped the prompt
from argv; and `process/run-cli-command` closed the child's stdin before
the process could read anything.

`ai.miniforge.cli.workflow-runner.preflight-test/run-backend-preflight-exercises-generic-cli-success-path-test`
failed on main asserting the prompt was the last argv element. That
assertion was stale, but the reason it was stale is a production defect,
not a test-only drift.

## Changes in Detail

### `components/llm`

- New `llm/backend-prompt-via` on the interface: the public accessor for a
  backend's declared prompt-delivery mode, so a caller building a backend
  command need not restate the `:argv` fallback from memory. The impl keeps
  its private `resolve-prompt-via` for the in-brick path; a new test asserts
  the two agree across every backend and on an undeclared map, so they
  cannot drift.

  The accessor is a separate defn rather than a re-export of the impl's
  private fn because publishing that var means editing
  `protocols/impl/llm_client.clj`, which fails the pre-commit stratum gate
  on a pre-existing SL003 (11 distinct layers, max 3) that also reproduces
  on untouched `origin/main`. Splitting that namespace is its own change,
  not this one.

### `bases/cli`

- `process/run-cli-command` accepts a `:stdin` option. The string is written
  to the child's stdin on a background thread and the stream is then closed;
  a nil/empty value just closes it, preserving the previous behavior for
  argv backends. Off-thread so a child that never drains stdin cannot block
  the caller past the command timeout.
- `preflight-probe/generic-preflight-command` becomes
  `generic-preflight-invocation`, returning `{:args :stdin}`. It threads the
  backend's declared `:prompt-via` into the `:args-fn` request and returns
  the prompt as stdin when the backend expects it there.

The Claude probe is unchanged: it does not go through `:args-fn`, and its
direct `-p <prompt>` form is a valid claude CLI invocation.

## Testing Plan

- `run-backend-preflight-exercises-generic-cli-success-path-test` now pins
  both halves of the codex contract: argv ends in `-` and carries no prompt
  text, and the probe pipes the prompt via `:stdin`.
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
real prompt to codex; a codex install that was silently "passing" preflight
on an empty prompt may now fail preflight, which is the intended
fail-closed behavior.

## Related Issues/PRs

- #1024 `fix(llm): send codex prompts via stdin` — introduced the contract
  the preflight probe did not follow.

## Checklist

- [x] Preflight namespace tests pass
- [x] llm interface + args-fn tests pass
- [x] No other caller of `llm/backends` `:args-fn` outside the llm brick
- [x] Apache 2.0 headers unchanged on all touched sources
