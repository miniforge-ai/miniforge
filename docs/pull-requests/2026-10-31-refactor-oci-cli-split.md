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
the pre-commit hook. The capsule credential stack (#2143, #2166, #2221) had review fixes that
edit it, and they should pass stratum-lint without warn mode. This PR was based on #2221, the
top of that stack, so the split contains every `oci_cli.clj` change the stack made. #2221 has
merged (16bc32489), and this PR's base is now main.

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
   shared JVM lock: 994 pass, with every review fix and main at 16bc32489 (#2221) merged in.

When the base moves, the split is regenerated from the base's `oci_cli.clj` and test files with
the same name-to-namespace assignment. The checks above are rerun, and this PR's own commits are
re-applied on top.

## Review Fixes

Capsule stack review fixes that edit `oci_cli.clj` land here, one commit each. Items 5 to 9 come
from the merge-gate review (rule 720 and the rules review).
Its comment on this PR lists the traced flow and what was left.
Items 10 and 11 are Copilot findings on #2221 whose code lives here.
Items 12 to 16 are Copilot findings on this PR in code the split moved:

1. A failed env-file write deletes the temp file (#2143 thread).
2. Persist and restore of a capsule cloned without an HTTP credential go to the URL it cloned
   (#2166 thread).
3. A caller's keyword-keyed `GIT_CONFIG_*` entries survive `:git-credentials?` (#2166 thread).
4. The no-credential fallback uses the URL bootstrap cloned. Bootstrap returns its `:clone-url`
   and acquire records it with the remote (a thread on this PR).
5. A Dockerfile read from a JAR is extracted into a directory of its own. The build context
   is that directory rather than the whole system temp directory (rule 720 re-trace of the
   moved image code). The moved image and copy functions, which had only Docker-gated tests,
   get unit tests in `oci-cli-image-test` and `oci-cli-container-test`.
6. The catch sites #2221 added in `container-git-count` and `image-env` use `try+`, and
   dag-executor's `deps.edn` declares slingshot (rule 211).
7. `security-gate-check` loses its four-argument arity, which read no image ENV and still passed
   the fail-closed check (rule 008).
8. `with-git-credentials` reads `execute!` env names as strings once. A caller's keyword
   `:GIT_TRACE` and the added `"GIT_TRACE"` can no longer both reach the env file (rule 010).
9. Smaller rules-review fixes: `network-command-for` and `recorded-remote` (002, 004), a let
   binding for the launch-plan env and `inspected-env-entry` (210).
   Other fixes include `try+` in `write-env-file!`
   (211), two section banners renamed for the code under them (001), and shared test helpers
   (400).
10. `git-trace-off-env` sets each trace target to `"0"`, not empty. git reads an empty
    `GIT_TRACE2`, `GIT_TRACE2_EVENT` or `GIT_TRACE2_PERF` as unset and falls back to a
    `trace2.*Target` in the image's system or global git config. With `trace2.envVars` naming a
    `GIT_CONFIG_VALUE_n`, that target records the Authorization header. A real-git test sets the
    three targets in a global config.
11. `with-git-credentials` puts the `execute!` env in string→string form with `normalize-env`
    (the launch plan's normalizer, renamed), which replaces `string-keys`. A numeric
    `GIT_CONFIG_COUNT` now reads as the `"1"` the env file writes, rather than failing the
    command with `:exec-failed`.
12. The git provider comes from the repository URL's host alone. `infer-host-kind` matched
    `github.com` or `gitlab` anywhere in the URL, so `https://github.com.attacker.example/o/r.git`
    was `:github`. Bootstrap sent the GitHub token to that host. Only `github.com` and
    `gitlab.com` name a provider now; every other host is `:generic`. A self-hosted GitLab needs
    env-config's `:host-kind :gitlab` to get `GITLAB_TOKEN`. Main has this leak today: the
    substring match is in main's `oci_cli.clj` (`infer-host-kind`), from 3c93876d5 (#445,
    2026-04-08), and every release since `ariadne/v1.6.0` carries it. Nothing configures
    enterprise hosts, so the exact host is the whole rule.
13. A bootstrap that throws removes its container. Acquire removed it only for a returned error,
    so a throw left a running container. A failed env-file write was one such throw;
    the caller never got the container id.
    Everything after container creation runs under `removing-unless-ok`.
14. Runtime output reads are bounded by the call's deadline. After the runtime CLI exited, both
    runners read stdout and stderr to end-of-stream. A child holding either open outlasted the
    exec, copy and release timeouts. One deadline now covers the exit and both reads. Output
    still open at the deadline closes and cancels its readers (`settle-drains!` in
    `runtime.process`). This destroys the process tree and returns the timeout result (exit 124),
    not potentially incomplete output. This matters because `image-env` uses that output to decide which image ENV
    entries to blank.
    A child the CLI left running is re-parented when the CLI exits.
    The process-tree kill cannot reach it and it runs on, as it does on main, where the call also
    blocks until it exits. Killing it needs each runtime call in its own process group, across
    every `runtime.process` call site; that is a follow-up PR.
15. An acquire that finishes after its deadline releases its container. The timeout returned
    `:timeout` while the interrupted body could still finish with an ok record, for example
    when the deadline fired during the image-digest read. That interrupted runtime call
    returns nil; the caller had no id to release that container with.
    `with-acquisition-timeout` settles the body's finish against the deadline with one
    compare-and-set. A body that finishes late hands its result to `abandon!`, which
    acquire uses to release the environment.
16. Cleanup is out of reach of the cancelling interrupt. The timeout marks the acquire abandoned,
    then interrupts it. A body that returned in between was already cleaning up (`abandon!`, or
    `removing-unless-ok`'s removal) when the interrupt landed. `without-interrupt` cleared
    the flag only on entry, so the interrupt could kill the removal. `without-interrupt` now
    runs the cleanup on a thread of its own and waits for it. It then sets a pending or new
    interrupt on the waiting thread again.

## Deployment Plan

Ships with the capsule credential stack; no configuration or data change.

## Related Issues/PRs

1. Precedent: #2300 (release-executor `sandbox.clj`), #2260 (artifact-session).
2. Was stacked on #2221. #2143, #2166, #2239 and #2221 have merged; this PR is the last.

## Checklist

- [x] Every definition moved unchanged (normalized comparison)
- [x] Each namespace within 3 layers
- [x] dag-executor and release-executor tests pass
- [ ] CI green, Copilot comments resolved
