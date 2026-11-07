# fix(llm): CLI output messages come from the message catalog

## Overview

`cli_output.clj` built its operator-facing messages from inline prose: the
Claude CLI rate-limit message (twice, streamed and not), the empty-output
message, and the error text a Codex failed turn or error event appends. The
file already looks its other messages up in the llm catalog. These now come
from `config/llm/messages/en-US.edn`, with the same text.

## Motivation

Found in the post-merge gate 2 review of #2089 (localization, dewey 050:
no raw prose in emitted output; named-constants: the same literal in two
places). #2089 rewrote the streamed rate-limit message line.

## Changes in Detail

- `en-US.edn`: `:cli-output.system/empty-success-output`,
  `:cli-output.system/claude-rate-limited` ("Claude CLI rate limited:
  {notice}"), `:cli-output.system/codex-stream-error` and
  `:cli-output.system/codex-error-unknown`.
- `cli_output.clj`: the sites call `msg/t`; `codex-error-delta` builds the
  Codex error text. An event whose message is nil now says "unknown" instead
  of nothing.

## Testing Plan

The text is unchanged; workflow's rate-limit detection reads the notice,
not the prefix. `interface-test` checks each message against its catalog
key, including a Codex error event with no message: 72 tests, 382
assertions, 0 failures.

## Deployment Plan

None.

## Related Issues/PRs

Follows #2089.

## Checklist

- [x] Tests updated
