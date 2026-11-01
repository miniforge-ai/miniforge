# fix(llm): CLI output messages come from the message catalog

## Overview

`cli_output.clj` built three operator-facing messages from inline prose:
the Claude CLI rate-limit message (twice, streamed and not) and the
empty-output message. The file already looks its other messages up in the
llm catalog. These three now come from `config/llm/messages/en-US.edn`,
with the same text.

## Motivation

Found in the post-merge gate 2 review of #2089 (localization, dewey 050:
no raw prose in emitted output; named-constants: the same literal in two
places). #2089 rewrote the streamed rate-limit message line.

## Changes in Detail

- `en-US.edn`: `:cli-output.system/empty-success-output` and
  `:cli-output.system/claude-rate-limited` ("Claude CLI rate limited:
  {notice}").
- `cli_output.clj`: the three sites call `msg/t`.

## Testing Plan

The text is unchanged; workflow's rate-limit detection reads the notice,
not the prefix. `interface-test` now pins both messages for the streamed
and the whole-output notice and the empty-output case: 72 tests, 381
assertions, 0 failures.

## Deployment Plan

None.

## Related Issues/PRs

Follows #2089.

## Checklist

- [x] Tests updated
