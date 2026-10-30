<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: mf webhook serve runs workflow triggers from GitHub webhooks

## Overview

Adds the intake's HTTP listener and `mf webhook serve`, a long-running command that feeds signed
GitHub webhooks into `workflow/create-event-trigger`.

## Motivation

`create-event-trigger` had no caller; nothing in miniforge started a workflow from an outside event.

## Changes in Detail

1. `webhook-intake/listener/start!`: http-kit on 127.0.0.1 unless a host is given, bodies capped at
   5 MiB, refuses to start without a webhook secret. GitHub cannot send a custom token header, so the
   signature is the token required for a public bind (the dashboard's rule from #2096 and #2226).
2. `mf webhook serve --triggers rules.edn [--host] [--port] [--secret-file]`: rules use
   `create-event-trigger`'s `{:triggers [...]}` format; each must name `:on` and `:run :workflow-id`.
   The secret comes from `MINIFORGE_GITHUB_WEBHOOK_SECRET` or `--secret-file`, never argv. The
   intake (default 127.0.0.1:8788) and the trigger share one event stream; triggered workflows get
   the options `mf run` passes `run-pipeline` (artifact store, event stream). Runs until
   SIGINT/SIGTERM, then stops the intake and trigger and closes the store.
3. The intake interface exports `sign` for clients and tests.

## Known limits

Triggered runs do not get `mf run`'s per-run manifest and event drain, and each matching event
starts its workflow at once with no concurrency limit. Both are fixed inside `create-event-trigger`;
`workflow/trigger.clj` is over the rule-210 layer budget and needs a split first. Keep trigger rules
narrow (`:repo`, `:branch-pattern`).

## Testing Plan

`listener-test`: refuses a blank secret, binds loopback, signed and unsigned deliveries over real
HTTP. `webhook-serve-test`: trigger-file validation, exit 1 without a file, with a bad file, or
without a secret; end to end, a signed delivery POSTed to the running intake reaches the stream the
trigger subscribes to (trigger stubbed, stream without sinks).

## Deployment Plan

Configure a GitHub webhook with content type `application/json` and the shared secret, pointed at a
tunnel or reverse proxy in front of the loopback listener.

## Related Issues/PRs

- #2266 (base): the `webhook-intake` component.

## Checklist

- [x] Stratum lint, clj-kondo, commit and PR budgets pass
- [ ] Copilot review comments answered
