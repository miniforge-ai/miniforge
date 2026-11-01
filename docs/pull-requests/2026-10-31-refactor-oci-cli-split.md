<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(dag-executor): split oci_cli.clj into nine namespaces (rule 210)

## Overview

The first commit is mechanical, with no behaviour change. It is a rule 210 split of
`components/dag-executor/src/ai/miniforge/dag_executor/protocols/impl/runtime/oci_cli.clj`
into nine namespaces and of its test file into three, each within the 3-layer budget. Later
commits on this branch carry the capsule credential review fixes that edit `oci_cli.clj`.

## Motivation

`oci_cli.clj` spans 8 call-graph layers against a budget of 3, so any edit to it fails SL003 in
the pre-commit hook. The capsule credential stack (#2143, #2166, #2221) still has review fixes
that edit it, and they should pass stratum-lint without warn mode. This PR is based on #2221,
the top of that stack, so the split already contains every pending `oci_cli.clj` change.

## Changes in Detail

`oci-cli` keeps the executor record (`OciCliExecutor`, whose class name is unchanged), its two
constructors and `ensure-all-images!`. The other 91 definitions move into:

1. `oci-cli.process`: the runtime command line, timed runs, the default timeouts.
2. `oci-cli.git-credentials`: repo URL parsing, token resolution, the per-command git config env.
3. `oci-cli.env`: the env file, trust-level env filtering, image ENV reading.
4. `oci-cli.image`: digests, existence, pull and build.
5. `oci-cli.container`: launch arguments, create, exec, copy, stop, remove, inspect.
6. `oci-cli.capsule-git`: the in-capsule credential env, the bootstrap clone, the workspace exec-fn.
7. `oci-cli.acquisition`: the acquisition deadline.
8. `oci-cli.launch`: the launch plan and the security gate.

A namespace reaches another's definitions through `:refer`, so no definition body changes. The
edits are:

1. The namespace forms.
2. `defn-` to `defn` (or dropping `^:private`) for 24 definitions another namespace now refers to.
3. The strata and Layer headings, which stratum-lint's own emitter assigns per file.

`executor.clj` names `oci-cli.image` for the four image functions it re-exports.

Tests follow the moved definitions:

1. `oci-cli/<name>` becomes `<alias>/<name>` for the namespace that defines it. That covers every
   `with-redefs`, since a redefinition has to name the var the code calls. Aliases are seven
   characters long, the same as `oci-cli`, so aligned bindings keep their columns.
2. `private-fn` resolves a name in whichever split namespace defines it.
3. The nine tests that use the stand-in runtime CLI move to `oci-cli-fake-runtime-test`.
   `oci-cli-test-support` holds the helpers both test namespaces use, and the stand-in runtime
   itself. 9 helpers become public there.

## Testing Plan

1. Form comparison: every top-level form of the old and new files was read with `*read-eval*`
   false. With `defn-` read as `defn` and `#()` gensyms renamed, the source side holds the same
   95 forms and the test side the same 84.
2. stratum-lint on every new file: no findings, and `--fix` leaves each file unchanged.
3. clj-kondo on dag-executor's src and test: no warnings beyond those already on the base.
4. dag-executor, release-executor, redaction, task-executor and llm-interface tests, under the
   shared JVM lock: 984 pass, with every review fix and #2221's head merged in.

When the base moves, the split is regenerated from the base's `oci_cli.clj` and test files with
the same name-to-namespace assignment, the checks above are rerun, and this PR's own commits are
re-applied on top.

## Review Fixes

Capsule stack review fixes that edit `oci_cli.clj` land here, one commit each. Items 5 to 9 come
from the merge-gate review (rule 720 and the rules review), whose comment on this PR lists the
traced flow and what was left:

1. A failed env-file write deletes the temp file (#2143 thread).
2. Persist and restore of a capsule cloned without an HTTP credential go to the URL it cloned
   (#2166 thread).
3. A caller's keyword-keyed `GIT_CONFIG_*` entries survive `:git-credentials?` (#2166 thread).
4. The no-credential fallback uses the URL bootstrap cloned. Bootstrap returns its `:clone-url`
   and acquire records it with the remote (a thread on this PR).
5. A Dockerfile read from a JAR is extracted into a directory of its own, so the build context
   is that directory rather than the whole system temp directory (rule 720 re-trace of the
   moved image code). The moved image and copy functions, which had only Docker-gated tests,
   get unit tests in `oci-cli-image-test` and `oci-cli-container-test`.
6. The catch sites #2221 added in `container-git-count` and `image-env` use `try+`, and
   dag-executor's `deps.edn` declares slingshot (rule 211).
7. `security-gate-check` loses its four-argument arity, which read no image ENV and still passed
   the fail-closed check (rule 008).
8. `with-git-credentials` reads `execute!` env names as strings once, so a caller's keyword
   `:GIT_TRACE` and the added `"GIT_TRACE"` can no longer both reach the env file (rule 010).
9. Smaller rules-review fixes: `network-command-for` and `recorded-remote` (002, 004), a let
   binding for the launch-plan env and `inspected-env-entry` (210), `try+` in `write-env-file!`
   (211), two section banners renamed for the code under them (001), and shared test helpers
   (400).

## Deployment Plan

Ships with the capsule credential stack; no configuration or data change.

## Related Issues/PRs

1. Precedent: #2300 (release-executor `sandbox.clj`), #2260 (artifact-session).
2. Stacked on #2221; merges after #2143, #2166, #2239 and #2221.

## Checklist

- [x] Every definition moved unchanged (normalized comparison)
- [x] Each namespace within 3 layers
- [x] dag-executor and release-executor tests pass
- [ ] CI green, Copilot comments resolved
