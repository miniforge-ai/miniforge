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
3. clj-kondo on dag-executor's src and test: the same 6 warnings as on #2221's head, none new.
4. Every dag-executor and release-executor test namespace, under the shared JVM lock: 752 tests pass.

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
