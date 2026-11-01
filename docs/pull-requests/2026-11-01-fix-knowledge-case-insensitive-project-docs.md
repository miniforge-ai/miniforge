<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Find project instruction files case-insensitively

## Overview

`knowledge/load-project-docs` now finds a project's instruction files
(`AGENTS.md`, `CLAUDE.md`, `.clauderc`, `claude_instructions.md`, and
`.claude/CLAUDE.md`) whatever the case of their names, on every filesystem.
Each file loads once, and its zettel records its real name.

## Motivation

The loader looked each file up by an exact lowercase name with
`(io/file root name)` and `.exists`. A case-insensitive filesystem (the macOS
default) also matched `AGENTS.md` and `CLAUDE.md` that way, but Linux does not.
Linux runs CI, cloud sessions and governed capsules. Uppercase `AGENTS.md` and
`CLAUDE.md` are the usual convention in the repos miniforge works on, so on
Linux those repos' instructions never reached the knowledge store.

## Changes in Detail

1. `knowledge/loader.clj`:
   - `project-doc-paths`: the instruction files as data, each a vector of path
     segments under the project root. It adds `.claude/CLAUDE.md`, the project
     file Claude Code loads.
   - `find-ignoring-case`: lists each directory on the path and keeps the
     entries whose name `equalsIgnoreCase` the segment. A listing returns each
     entry once under its real name. On a case-insensitive filesystem a file
     therefore matches once, not once per spelling.
   - `distinct-files`: drops a file that resolves (`toRealPath`) to one
     already kept, so a `CLAUDE.md` symlinked to `AGENTS.md` loads once, as
     `AGENTS.md`.
   - `load-markdown-file` takes the file's root-relative `origin`, with its
     real name. That origin is `:zettel/source :origin`, and the uid is the
     origin without `.md`. `.claude/CLAUDE.md` gets the uid `.claude/CLAUDE`,
     so it does not overwrite a root `CLAUDE.md` in the store.
2. `knowledge/interface.clj`: the `load-project-docs` docstring describes the
   case-insensitive match, the single load per file, and what `:files` holds.

## Behaviour changes

- On Linux, a root with uppercase `AGENTS.md` / `CLAUDE.md` now loads them;
  `:files` lists their real names.
- `.claude/CLAUDE.md` loads when present. In this repo it is a 14-line stub
  that imports `agents.md`, so `initialize-knowledge-store!` here now loads
  three doc zettels instead of two.
- On macOS, an uppercase `CLAUDE.md` used to be recorded under the spelling
  the loader asked for: origin `claude.md`, uid `claude`. It is now recorded
  under its real name: origin `CLAUDE.md`, uid `CLAUDE`. Lowercase files keep
  their old origin and uid.
- On a case-sensitive filesystem, a root with both `claude.md` and
  `CLAUDE.md` loads both: they are different files.

## Testing Plan

`components/knowledge/test/ai/miniforge/knowledge/loader_test.clj`, each case
in a temp project root:

1. Lowercase `agents.md` only: loads, `:files` is `["agents.md"]`.
2. Uppercase `AGENTS.md` and `CLAUDE.md`: both load, each once, with their
   real names as origin.
3. `CLAUDE.md` plus `.claude/CLAUDE.md`: both load, with distinct origins and
   uids.
4. `CLAUDE.md` symlinked to `AGENTS.md`: one zettel, origin `AGENTS.md`.

Cases 2–4 fail against the old loader (8 failed assertions); all pass with
this change.

`context-pack`'s `interface_test.clj` passes `"claude.md"` in
`:files-in-scope`. That reads this repo's own lowercase `claude.md` through
`repo-index`, not through this loader, so it is unaffected.

## Deployment Plan

No configuration or migration.

## Related Issues/PRs

None.

## Checklist

- [x] Regression tests for each behaviour change
- [x] No user-facing strings added
- [x] Stratum metadata consistent in edited namespaces
