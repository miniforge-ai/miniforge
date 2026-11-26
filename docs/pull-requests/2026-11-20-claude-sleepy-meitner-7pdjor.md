<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(lsp-mcp-bridge): never overwrite an unparseable Claude config

## Overview

`lsp:setup` merges a `miniforge-lsp` entry into a client's MCP config.
It now refuses to write when it cannot read the existing config, and it
writes every config through a temp file and an atomic rename.

## Motivation

`tasks/read-json-file` returned `{}` when an existing file failed to
parse. `setup-claude-code` and `setup-claude-desktop` merged into that
result and wrote it back. A `.mcp.json` or Claude Desktop config with a
syntax error was replaced by a file holding only `miniforge-lsp`. Every
other MCP server the user had configured was dropped.

## Changes in detail

- `read-json-file` returns `{}` for a missing or blank file. A file
  that is not exactly one JSON object throws an `ex-info` naming the
  path, before any write. Content after the first JSON value counts as
  unparseable.
- `write-file-atomically!` writes a unique sibling temp file and moves
  it over the target. `write-json-file` and `setup-codex` use it.
- The temp file is created owner-only. When the target exists, the temp
  file takes the target's POSIX mode before any content is written.
- A symlinked config is written through to its target; the link stays.
  The link chain is followed by hand, so a link whose backing file is
  missing is kept and the backing file is created. A link cycle throws
  after 40 hops and writes nothing.
- Reads follow links the same way as writes, through `read-config-text`.
  Only a missing file reads as empty. Any other read failure throws
  before anything is written, for the JSON configs and the Codex TOML.
- A config created for the first time is owner-only. The previous
  in-place write used the process umask.
- `setup` stops at the first client whose config cannot be read. Later
  client flags in the same command are not processed.

## Testing plan

New `tasks_test.clj`, 6 tests and 20 assertions:

- `read-json-file` on a missing file, valid JSON, truncated JSON,
  trailing content, a non-object root and a blank file.
- `write-json-file` creates parent directories and leaves only the
  config in its directory.
- `write-file-atomically!` writes through a symlink, keeps an
  owner-only mode and leaves no temp file.
- A link with a missing backing file is kept and its backing file is
  created.
- A link cycle throws and leaves both links in place.
- A chain of 45 links throws on read and on write. The backing config
  is untouched.

The namespace passes on the JVM. Under Babashka, `bb lsp:setup
--claude-code` was run in a scratch project. A config with trailing
content made it throw and stayed byte-for-byte the same. A valid config
kept its other server and gained `miniforge-lsp`.

## Deployment plan

No configuration or data change.

## Known follow-up

`tasks.clj` already has four layers on `main`, one over the budget of
three (SL003). This change adds no layer. The local commit gate blocks
further edits to the file until the namespace is split.

## Related issues/PRs

Based on `main`. No dependencies.

## Checklist

- [x] New tests pass on the JVM
- [x] Namespace loads under Babashka
- [x] Merged with current `main`
- [ ] CI green on the final head
- [ ] Review threads resolved
