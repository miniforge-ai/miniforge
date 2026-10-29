<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Never overwrite a config.edn that does not parse

## Overview and motivation

When `~/.miniforge/config.edn` failed to parse (a hand-edit typo, or a read
racing another process's non-atomic write), `mf config set` and
`mf config backend` printed a warning, then wrote the defaults plus one key
over the file. The LLM backend, policy packs, fleet repos and every other
setting were lost, with no backup. A run with an unparseable config also
silently used the defaults.

This was the top data-loss finding of the 2026-10-28 production-readiness
review (on-disk-state reviewer).

## Layer and dependencies

Foundation plus CLI adapters, based on main. The follow-up PR for the TUI
(pr-sync) and dashboard repo writers stacks on this one.

## Changes

1. `components/config`: new `ai.miniforge.config.interface.file` with
   `update-config!` and `write-config!`. A writer takes an in-process monitor,
   then a FileChannel lock on the sidecar `<path>.lock`, polled for 500 ms. It
   re-reads the file under the lock and publishes by renaming a forced sibling
   temp file over it. The temp file gets the old mode first; a symlinked config
   is written through to its target. `update-config!` returns an
   `:invalid-input` anomaly naming the file and the parse error, and writes
   nothing, when the file is not a single EDN map (malformed, not a map, or
   several top-level forms). It skips the write when nothing changed.
   `write-config!` replaces whatever is there, for `config reset`.
2. The lock is a sidecar file because a lock on `config.edn` itself stops
   guarding the path once an atomic rename swaps the inode.
3. The namespace is separate from `config.interface` so the bb lsp tasks, which
   load `config.interface` on a minimal classpath, need no anomaly,
   file-durability or slingshot.
4. CLI: `config set` and `config backend` use `update-config!`; on an anomaly
   they print it to stderr and exit 1. `init` and `edit` create the file under
   the lock, keeping one another writer created first; `reset` uses
   `write-config!`. `fleet add` and `fleet remove` update only
   `[:fleet :repos]` under the same lock, report errors on stderr, and no
   longer copy the defaults into the file.
5. `config.user/read-edn-file` prints a warning naming the file and the parse
   error to stderr instead of returning nil silently. Reads still fall back to
   the defaults.

## Verification plan

One regression test per writer: put a truncated map in a temp config file,
call the writer, assert the file is byte-for-byte unchanged and an anomaly (or
exit 1 with the path on stderr) comes back. Each fails against main's code.

Shared-writer tests cover absent and blank files, keeping other keys, no temp
file left behind, a caller's `*print-length*`, mode and symlink preservation,
16 concurrent writers all landing, and `write-config!` replacing a broken file.
They run on the JVM and under babashka.

## Scope and deployment

Normal CLI deployment; no config format change. A `config.edn.lock` file now
appears beside `config.edn`. A config.edn created by the new writer is 0600
(the temp file's mode); an existing file keeps its mode but not its owner, so a
`sudo` write leaves a root-owned file.

If fsyncing the parent directories fails after the rename, the writer returns a
distinct anomaly saying the file was replaced but may not survive a crash.
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

1. Follow-up PR: pr-sync (TUI `:repo add/remove`, discovery) and dashboard
   repo writers, which replaced the whole config with `{:fleet {:repos [...]}}`
   on a parse error.
2. `config.user/update-user-config` has the same defaults fallback but no
   callers; left for a separate change.
