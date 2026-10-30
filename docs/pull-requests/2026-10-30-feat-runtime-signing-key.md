<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(runtime-signing): runtime signing key (owner-only) and HMAC signatures

PR #2269.

## Overview

New `runtime-signing` component: a runtime-held key in an owner-only file and HMAC-SHA256 signatures over a lossless
canonical encoding. Follow-up PRs sign grants at issue and effect envelopes at mint, and verify both before an effect
commits.

## Motivation

Nothing in the runtime could sign the authority it issues. Grant and effect records on disk are trusted on their
contents, so a process that can write those stores can write authority.

## Changes in Detail

1. `sign` returns the hex HMAC-SHA256 of `canonical/encode` of a value. The encoder sorts map entries and set members by
  their own encodings, prints strings readably under any caller printer binding, and refuses (throws) a map or set whose
  entries would encode alike instead of collapsing them, so distinct values never share an encoding. `signed?` compares
  in constant time and returns false instead of throwing on untrusted input.
2. `load-or-create-key!` reads `<miniforge-home>/keys/runtime-signing.key` (or a given file), creating it when absent:
   1. The 32-byte key is written to a temporary file that `Files/createTempFile` creates `rw-------` (a `java.io.File`
     write would apply the umask), forced to disk, then hard-linked into place. An existing or concurrently created key
     is never replaced.
   2. Refused with an anomaly: a key directory that is a link, owned by another user, or writable by group or others; a
     key file that is a link, owned by another user, or open to other users; a file over 256 bytes or not exactly 64 hex
     characters after trimming. The file is read to EOF through a channel opened with NOFOLLOW_LINKS.
   3. On a file system without POSIX permissions the key is created without them.
3. Registered on the dev and test classpaths. Projects pick it up when execution-grant depends on it.

Limit, stated in the namespace docstring: a worker that can write the grant or effect stores but cannot read the key
file cannot forge authority. A process running as the same user can read the key, so this alone does not contain
local-mode agents.

## Testing Plan

1. `runtime-signing.interface-test`: a new key is `rw-------` and stable across loads; a group-readable, symlinked, or
  malformed key file, a padded key with trailing garbage, and a world-writable key directory are refused; a signature
  verifies, survives key reordering, and fails for a changed value, another key, nil, or a wrong signature; printer
  bindings (`*print-length* 0`, `*print-readably* false`) change nothing; `#{1 1.0}` and `#{1}` sign differently; a set
  of an Instant and a Date at the same moment is refused.
2. Babashka: with the dev classpath, `bb` created a key `rw-------`, read it back unchanged, and verified a signature.
  The existing graalvm compatibility test requires every component under bb, this one included, and passes. That test
  file is already over the stratum limit on main, so I did not add a call-path test to it.

## Deployment Plan

No behavior change until grants and envelopes use it.

## Related Issues/PRs

Part of the effect-authority work with #2258, #2262, #2265.

## Checklist

- [x] Commits under 200 reportable lines; PR under 600
- [x] Runs under babashka
- [x] PR doc: `docs/pull-requests/2026-10-30-feat-runtime-signing-key.md` (rule 721)
