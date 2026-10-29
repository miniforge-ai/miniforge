<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Never overwrite a config.edn that does not parse

## Overview and motivation

A hand-edit typo or a read racing a non-atomic write could make `~/.miniforge/config.edn` fail to parse.
Then `mf config set` and `mf config backend` printed a warning and replaced the file with defaults plus one key. The LLM
backend, policy packs, fleet repos and every other
setting were lost, with no backup. A run with an unparseable config also
silently used the defaults.

This was the top data-loss finding of the 2026-10-28 production-readiness
review (on-disk-state reviewer).

## Layer and dependencies

Foundation plus CLI adapters, based on main.
Four PRs stack on this one: dashboard repo writers, pr-sync stratum autofix, pr-sync TUI repo writers, and the `config
set llm.backend` keyword fix.
The TUI writers follow the mechanical stratum-lint PR for `pr_sync/core.clj`.

## Changes

1. `components/config`: new `ai.miniforge.config.interface.file` with
   `update-config!` and `write-config!`. A writer takes an in-process monitor,
   then polls for a FileChannel lock on sidecar `<path>.lock` for 500 milliseconds. It
   re-reads the file under the lock and publishes by renaming a forced sibling
   temp file over it. The temp file gets the old mode first; a symlinked config
   is written through to its target. `update-config!` refuses files that are not a single EDN map: malformed input,
   non-map forms, or several top-level forms.
   It returns an `:invalid-input` anomaly naming the file and parse error, without writing. It skips the write when
   nothing changed.
   `write-config!` replaces whatever is there, for `config reset`.
2. The lock is a sidecar file because a lock on `config.edn` itself stops
   guarding the path once an atomic rename swaps the inode.
3. The namespace is separate from `config.interface`.
   The bb lsp tasks load `config.interface` on a minimal classpath and need no anomaly, file-durability, or slingshot dependency.
4. CLI: `config set` and `config backend` use `update-config!`; on an anomaly
   they print it to stderr and exit 1. `init` and `edit` create the file under
   the lock, keeping a file another writer created first; `reset` uses
   `write-config!`. `fleet add` and `fleet remove` update only
   `[:fleet :repos]` under the same lock, report errors on stderr, and no
   longer copy the defaults into the file.
5. `config.user/read-edn-file` prints a warning naming the file and the parse
   error to stderr instead of returning nil silently. Reads still fall back to
   the defaults.

## Verification plan

Each writer has a regression test using a truncated map in a temp config file.
The writer must leave the file byte-for-byte unchanged and return an anomaly, or exit 1 with the path on stderr. Each
fails against main's code.

Shared-writer tests cover absent and blank files, keeping other keys, temporary-file cleanup, and a caller's `*print-length*`.
They also cover mode and symlink preservation, 16 concurrent writers all landing, and `write-config!` replacing a broken
file.
They run on the JVM and under babashka.

## Scope and deployment

Normal CLI deployment; no config format change. A `config.edn.lock` file now
appears beside `config.edn`. A config.edn created by the new writer is 0600
(the temp file's mode). An existing file keeps its mode but not its owner.
A `sudo` write leaves a root-owned file.

If fsyncing parent directories fails after rename, the writer returns a distinct anomaly.
It says the file was replaced but may not survive a crash.
Windows skips that directory fsync, since Java cannot open a directory there.

The 500 ms lock bound covers other processes. Threads in one JVM queue on a
monitor with no timeout. A dangling symlink at the config path is replaced by a
regular file.

A process still running the old code locks `config.edn` itself, so during an
upgrade old and new writers do not exclude each other. For the same reason, the
pr-sync PR (TUI writers) should merge before the dashboard PR; until it does,
the TUI still locks `config.edn` itself. Comments in a config
file are still lost on any rewrite (unchanged).

## Related work

1. Stacked follow-ups: dashboard repo writers; pr-sync stratum autofix, then
   the pr-sync (TUI `:repo add/remove`, discovery) repo writers; `config set`
   keyword values and unknown-backend failure.
2. `config.user` writers (`update-user-config`, `save-user-config`,
   `init-user-config`, `reset-user-config`) still skip the lock and atomic
   write, and `update-user-config` keeps the defaults fallback. None has a
   caller; left for a separate change.
