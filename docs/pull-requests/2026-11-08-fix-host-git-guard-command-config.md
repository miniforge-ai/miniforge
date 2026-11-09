<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: host-git guard watches config that runs commands

## Overview

The host-git guard now also compares git config keys that make git run a
program or load other config, such as `core.hooksPath`, `core.fsmonitor`,
`credential.helper`, `include.path`, and `filter.<driver>.clean`. A change
to any of them during a task run makes the drift report dirty
(`:clean? false`), the same as a changed remote URL.

## Motivation

A local-mode task runs in a linked worktree, which shares `.git/config` with
the host checkout. Running `git config core.hooksPath <dir>` in the task
worktree writes the host's config (checked with git 2.48.1). The host's next
commit, the operator's own included, then runs hooks from that directory.
Until now the guard compared only remote URLs, `insteadOf` rewrites, and
remote-tracking refs, so this change went unreported.

Ordinary task work (editing, building, testing, committing) sets none of
these keys, so a change to one counts toward `:clean?`.

## Changes in Detail

1. `host-git-guard/command-config-pattern`: the watched keys. They are
   `core.hooksPath`, `core.fsmonitor`, `core.sshCommand`, `core.askPass`,
   `core.gitProxy`, `core.pager`, `core.editor`, and
   `core.alternateRefsCommand`. Also watched: `sequence.editor`, `pager.*`,
   `alias.*`, `interactive.diffFilter`, `include.path`,
   `includeIf.*.path`, `credential.helper` and `credential.<url>.helper`,
   and `filter.*.clean|smudge|process`. The rest are `diff.external`,
   `diff.*.command|textconv`, `merge.*.driver`,
   `difftool|mergetool.*.cmd|path`, `gpg.program`, `gpg.<format>.program`,
   `gpg.ssh.defaultKeyCommand`, `remote.*.uploadpack|receivepack|vcs`,
   `gc.recentObjectsHook`, `uploadpack.packObjectsHook`, `man.viewer`,
   `man|browser.*.cmd|path`, `web|help|instaweb.browser`,
   `instaweb.httpd`, `guitool.*.cmd`, `trailer.*.command|cmd`,
   `imap.tunnel`, and the `sendemail` command and server keys. The list
   comes from a sweep of the `git-config` keys of git 2.48.1 that name a
   program or command.
2. The snapshot gains `:command-config`, and `drift` gains
   `:command-drift`. `:clean?` is now false when either `:redirect-drift`
   or `:command-drift` is non-empty.
3. Command-config values are never held. Each one is replaced by
   `fingerprint`, an HMAC-SHA256 truncated to 128 bits, keyed by random
   bytes made once per process. A command line can carry a secret in a form
   no pattern can find, such as an inline credential helper that prints a
   token. Drift only needs equality, and both snapshots are taken in the
   same process. A fingerprint seen in a log cannot be checked against a
   guessed value.
4. Keys are now redacted with `redact-credentials` as well as values. A
   `<url>` subsection (`url.<base>.insteadOf`, `credential.<url>.helper`)
   can carry userinfo.
5. Config is read with `git config --null --get-regexp`, which ends each
   entry with NUL. The old line-based parse split a multi-line value (an
   inline helper, a multi-line alias) into extra entries, and the first
   word of a continuation line became a key. A key is not fingerprinted, so
   a line such as `password=<token>; ...` would have kept the token.
   A valueless key (a bare `log` under `[pager]`, which git reads as
   true) is kept as the marker `valueless-entry`, not dropped. Dropping
   it would let a task turn on a pager or `core.fsmonitor` unseen.
6. `redirect-config` becomes `config-values`, which takes the pattern, the
   value scrubber, and the failure message. `changed-redirect-config` is
   renamed `changed-config`, since it now compares both classes.
7. Messages: new `:snapshot/command-config-failed`. The text of
   `:guard/host-drifted-during-run` now covers both classes.

## Alternatives rejected

1. Keep command values and URL-redact them, as remote URLs are. A command
   line can hold a secret in a form that is not a URL.
2. Fingerprint with plain SHA-256. A logged digest of a short secret could
   be checked against guesses offline. The per-process key prevents that.
3. One combined config read, then classify each key. Two reads with their
   own pattern and scrubber keep the two classes apart, at the cost of one
   extra `git config` process per snapshot.

## Behavior change

The read takes no scope flag, as before, so the user's global config is in
the snapshot. An operator who edits `~/.gitconfig` during a run, for example
by adding an alias, now gets a drift verdict for the checkout. The verdict warns on later
acquires and fails the release result. It does not refuse an acquire.
Before this change only a global remote URL or `insteadOf` edit did that.

## Testing Plan

1. `command-config-pattern-selects-keys-that-run-programs-test`: every
   listed key matches. Neighbouring keys that run nothing
   (`core.autocrlf`, `remote.origin.fetch`, `filter.lfs.required`,
   `submodule.<name>.update`, and others) do not.
2. `fingerprint-tells-values-apart-without-holding-them-test`: equal
   values give equal fingerprints, different values (the empty string
   included) give different ones, and the fingerprint does not contain the
   value.
3. `hooks-path-set-inside-a-linked-worktree-is-drift-test` (real git):
   `git config core.hooksPath` in a linked worktree makes the host's report
   dirty, with `core.hookspath` in `:command-drift` and nothing in
   `:redirect-drift`.
4. `a-credential-helper-never-reaches-a-snapshot-test` (real git): a token
   in a `credential.<url>.helper` key and on the second line of its value
   appears nowhere in the snapshot. The value is one fingerprint under the
   redacted key. A line-based parse fails this test.
5. `a-valueless-key-added-mid-run-is-drift-test` (real git): a bare `log`
   under `[pager]` appended to the shared config is reported in
   `:command-drift` as `:valueless`.
6. Existing guard and guarded-executor tests pass unchanged, apart from the
   `changed-config` rename. The fetch and force-push cases still report
   clean, so command config does not drift on an ordinary fetch.
7. By hand: the namespace loads and snapshots this checkout under babashka,
   using the `miniforge` bb task's classpath. `java.util.HexFormat` is not
   available in babashka, so hex is built with `format`.

## Deployment Plan

None. The guard wraps the worktree executor and takes effect on the next
run.

## Known gaps

1. Hook scripts written into the common dir's `hooks/` directory are files,
   not config, and are not watched. A task can write
   `$(git rev-parse --git-common-dir)/hooks/post-commit` directly.
2. `submodule.<name>.update` is not watched. Its `!command` form runs a
   shell command, but `git submodule init` copies the key from
   `.gitmodules`, so an ordinary submodule checkout in a task sets it.
3. Two keys that differ only in the userinfo of a URL subsection redact to
   the same key, so a change of that userinfo alone is not reported. Values
   already had the same limit.
4. Detection only, as before. A clone instead of a linked worktree is what
   prevents this, and that is a later design.

## Related Issues/PRs

Base branch: `main`. Depends on: nothing.

1. Follow-ups 2 and 3 of the task snapshot history design (#2463,
   `docs/design/task-snapshot-history.md`), from the 2026-11-08
   PAODO-Workspaces comparison.
2. Original guard: `docs/pull-requests/2026-08-06-host-git-guard.md`.
3. Companion PR (not yet open): `archive-bundle!` runs `git add` and `git commit` with
   hooks and fsmonitor off.

## Checklist

- [x] Command config watched and counted toward `:clean?`
- [x] Values fingerprinted, keys redacted
- [x] `--null` parse for multi-line values
- [x] Messages updated
- [x] Unit and real-git tests
- [x] Loads under babashka
