<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: fail a run whose task worktree mutated the host checkout

**Theme:** executor isolation
**Stack:** third of three. Base is the host-git guard, which added the
observation this PR enforces on.

## Problem

The worktree executor provisions each task as a linked `git worktree` of the
host checkout, which shares `.git/config` and every ref outside HEAD. The
mechanics and the 2026-08-06 incident are set out in the previous PR's doc.

What the investigation added is that **the exposure is not latent**. Two paths
already run remote-mutating git inside a task worktree on the host:

- `release-executor/sandbox.clj:533` — `create-branch!` issues
  `git fetch origin <default-branch>` through the DAG executor. In local mode
  that executor is the worktree executor, whose `execute!` runs `sh -c` in the
  task worktree, so the fetch writes the host's `refs/remotes/origin/*`.
- `release-executor/sandbox.clj:244` and `release-executor/git.clj:403` — the
  HTTPS-token push fallback does
  `git remote set-url origin <url-with-token>`, pushes, then restores. Same
  routing. A process that dies between the set and the restore leaves a
  **GitHub token persisted in the host checkout's `.git/config`**.

`release-executor/core.clj` picks sandbox mode whenever `:executor` and
`:environment-id` are both present, which is exactly the `bb dogfood` shape;
host mode is reached only when both are absent. Beyond those two, an agent
runs arbitrary git inside its task worktree, and any `git config`,
`git remote`, or `git fetch` it issues lands on the host.

## Remedy chosen, and the ones rejected

**Chosen: a fail-closed invariant check across each task run**, wrapping the
worktree executor rather than replacing the worktree mechanism.

**`extensions.worktreeConfig` — rejected, and it does not work.** The claim is
that it gives a worktree its own config. Tested:

```text
$ git -C wt config extensions.worktreeConfig true     # writes the HOST config
$ git -C wt config --worktree remote.origin.url https://example.invalid/PERWT.git
$ git -C wt remote set-url origin https://example.invalid/EVIL2.git
$ git config -f host/.git/config --get remote.origin.url
https://example.invalid/EVIL2.git                     # host still corrupted
$ git -C wt config --get remote.origin.url
https://example.invalid/PERWT.git                     # sandbox reads clean
```

A plain `git remote set-url` — what an agent actually types — still writes the
shared config. All the per-worktree override buys is that the sandbox no
longer *sees* the damage it did, which is worse than nothing. It fixes no
refs, and enabling it is itself a write to the host's config.

**Clone-based sandboxes — the real fix, deferred.** A clone does not share a
common dir, so it prevents rather than detects. It is not one PR; see
Follow-ups for the four things it drags with it.

**A guard that only warns — rejected as insufficient.** Every current caller
of `release-environment!` discards its result: `with-environment` calls it in
a `finally`, `release-execution-environment!` wraps it in a bare `try`. A
returned error alone would have been as silent as the leak.

## Changes in Detail

### `protocols/impl/host_guarded.clj` + `.../host_guarded/lifecycle.clj` (new)

A `TaskExecutor` that wraps another and holds it to the host-git invariants.
`executor-type` reports the **delegate's** type, so the governed-mode capsule
check in `runner-environment/select-capsule-executor`, which branches on
`:worktree`, is unaffected.

`acquire!` snapshots the host checkout, then delegates. `release!` delegates
first and unconditionally — a drifted host is a reason to fail the run, never
a reason to strand a worktree on disk — then re-reads and compares.

On drift the release returns `:host-git-drift`, warns on stderr, and records a
**verdict** against that checkout; every later acquire on it is refused for the
life of the process. That is what makes this fail-closed rather than advisory:
`acquire-environment!` errors do propagate, and on 2026-08-06 the branches cut
from the corrupted checkout after the damage inherited it. Continuing to hand
out worktrees of a checkout known to be drifted is the wrong default.
`reset-guard-state!` clears a verdict once an operator has repaired the
checkout.

A snapshot that cannot be read fails acquisition rather than proceeding
unwatched. That costs nothing: the reads are `git config` and
`git for-each-ref` against the same path `git worktree add` is about to be
handed, so a path where they fail is a path where the delegate would fail too.

Split across two namespaces because the record sits a layer above the
lifecycle steps and its factory a layer above that — four bands in one file,
which rule 210 answers with a split rather than a flattening.

### `executor.clj`

`create-executor-registry` wraps the `:worktree` entry. It is the one place
every caller goes through — the runner, the governed worktree-plus-capsule
path, and the interface re-export all build from it. Kubernetes and OCI
entries are left unwrapped: their environments have their own git dir.

## Layer

`dag-executor` component. `host-guarded` is Layers 0-1; `host-guarded.lifecycle`
is Layers 0-1. No brick dependencies added.

## Testing

A real host checkout, a real `git worktree add`, and a real config write from
inside the task worktree — the whole lifecycle rather than a stubbed delegate,
because the leak only exists through git's shared common dir. A stub would
pass these tests with the guard removed.

- A clean run releases exactly as the unwrapped executor does. This is the
  regression that matters most: the worktree executor is the fallback
  `bb dogfood` uses.
- A run that repoints origin fails its release and warns.
- The checkout is then condemned, and the next task is refused *before* any
  worktree is provisioned for it. Asserted with a distinct task id, so a stale
  branch or directory left by the first task cannot be what makes it pass.
- Resetting the guard state lets acquisition resume once the checkout is
  repaired.

Verified the guard is load-bearing rather than assumed — the same run through
an **unwrapped** worktree executor:

```text
UNGUARDED release ok? => true
host origin after     => https://example.invalid/redirected-mirror.git
```

`dag-executor` component suite green: 423 tests, 1745 assertions, including
the pre-existing worktree executor and lifecycle tests that pin
`resolve-branch-sha`, the three-attempt create fallback, and the
archive/restore paths. `bb poly:check` clean (only pre-existing warnings
202/205/207).

## What this does NOT fix

Stated plainly, because the gap is real:

- **It does not prevent anything.** A task run can still rewrite the host's
  config and refs. The guard notices afterwards and stops the run and every
  later run on that checkout, but the damage still has to be repaired by hand.
- **`refs/heads` is not watched.** `git worktree add -b task-<id>` creates a
  branch in the host by design, so task branches cannot be told apart from
  agent-created ones without a naming rule this PR does not introduce.
- **Config keys other than remote URLs are not watched** — `user.email`,
  `core.hooksPath`, `credential.helper` and the rest are equally shared and
  equally writable from a task worktree.
- **Detection is per-process.** A verdict does not survive a restart, and
  drift that happened before the guard ever ran is invisible to it.
- **The release-executor's `set-url`/restore pair is untouched.** The guard
  catches a failed restore after the fact; it does not stop the token being
  written in the first place.
- **`worktree.clj` itself is unchanged** — see follow-up 3 for why it could
  not be.

## Follow-ups

1. **Stop writing tokens into git config at all.** Replace the
   `set-url` → push → restore sequence in `release-executor/git.clj:399-439`
   and `sandbox.clj:227-265` with a push straight to the token-bearing URL
   (`git push https://x-access-token:TOKEN@github.com/o/r.git <refspec>`).
   Nothing is written to config, so there is nothing to restore and nothing to
   leak when the process dies mid-sequence. Both files' tests currently pin
   the set-url/restore pair, so that is a behaviour change to make
   deliberately. Highest-value remaining item.
2. **Clone-based task sandboxes**, behind a config flag defaulting off until
   proven on a dogfood run. Requires: `git clone --local` (hardlinked objects,
   so the marginal disk cost is near nil — the working-tree checkout is
   already paid today); setting the clone's `origin` to the *host's origin
   URL*, not the host path, or every release push would land in the host repo
   instead of GitHub; threading the host repo path into
   `notify-file-written!` so `scratch-commit!` keeps writing scratch refs
   somewhere that survives release; and `rm -rf` in place of
   `git worktree remove` on teardown.
3. **`worktree.clj` cannot currently be edited.** It has no `Layer N`
   headings, so stratum-lint skips it — but staging it triggers `--fix`, which
   produces a 919-line rewrite and then fails SL003 at **7 distinct layers**,
   blocking the commit. Any future change to the worktree executor needs that
   namespace split first. That is why this PR wraps the executor from the
   registry rather than modifying it.
4. **Watch more of the shared config surface** — at minimum `core.hooksPath`
   and `credential.helper`, both of which turn a task worktree into host code
   execution or credential capture.
