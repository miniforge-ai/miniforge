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
   they print it to stderr and exit 1. `init`, `edit` and `reset` use
   `write-config!`. `fleet add` and `fleet remove` update only
   `[:fleet :repos]` under the same lock and no longer copy the defaults into
   the file.
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
appears beside `config.edn`.

A process still running the old code locks `config.edn` itself, so during an
upgrade old and new writers do not exclude each other. Comments in a config
file are still lost on any rewrite (unchanged).

## Related work

1. Follow-up PR: pr-sync (TUI `:repo add/remove`, discovery) and dashboard
   repo writers, which replaced the whole config with `{:fleet {:repos [...]}}`
   on a parse error.
2. `config.user/update-user-config` has the same defaults fallback but no
   callers; left for a separate change.
