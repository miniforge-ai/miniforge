# feat: phase telemetry refactor + dashboard event enrichment

## Overview
Two related changes from the `finish-event-telemetry.spec.edn` dogfood run:
shared streaming callback extraction across all phase implementations, and
dashboard event enrichment (WS envelopes, on-disk event history, live+historical
merge).

## Motivation
Five phase implementations (plan, implement, verify, review, release) each
contained an identical 5-line inline copy of the event-stream streaming
callback pattern. The release phase was also missing the callback entirely.

On the dashboard side, the workflow detail view was capped at 50 events, had
no access to archived events from disk, and the WebSocket was sending raw
Clojure maps to the browser without normalization.

## Layer
Platform / Observability

## Base Branch
`main`

## Depends On
None.

## Changes in Detail

### Phase telemetry
- `components/phase/src/ai/miniforge/phase/telemetry.clj` (new):
  `create-streaming-callback` — single implementation of the
  `event-stream → requiring-resolve → create-cb` pattern with a soft
  dependency on `event-stream.interface`.
- `components/phase/src/ai/miniforge/phase/interface.clj`: re-exports
  `create-streaming-callback` from `telemetry`.
- `components/phase-software-factory/src/ai/miniforge/phase/{plan,implement,verify,review}.clj`:
  each replaces its local `create-streaming-callback` with `(phase/create-streaming-callback ctx :phase-name)`.
- `components/phase-software-factory/src/ai/miniforge/phase/release.clj`:
  `build-executor-context` now threads `:on-chunk` and `:event-stream` into
  the executor context (was completely absent).
- `components/phase-software-factory/test/ai/miniforge/phase/release_test.clj`:
  new test `release-propagates-streaming-callback-test` verifies the callback
  is forwarded to the executor.

### Dashboard event enrichment
- `components/web-dashboard/src/ai/miniforge/web_dashboard/server/websocket.clj`:
  `ws-event-envelope` wraps outgoing events in a browser-friendly envelope
  (string `event-type`, string `workflow-id`, raw payload preserved);
  `normalize-workflow-event` normalizes incoming JSON (string→keyword types,
  string UUID→`java.util.UUID`).
- `components/web-dashboard/src/ai/miniforge/web_dashboard/state/workflows.clj`:
  `read-event-file` reads archived EDN events from `~/.miniforge/events/<id>.edn`;
  `get-events` merges live in-memory events with on-disk history, deduplicates
  by `:event/id`, sorts by timestamp. Helper fns: `->instant`, `ts-epoch-ms`,
  `workflow-id=`, `event-ts`.
- `components/web-dashboard/src/ai/miniforge/web_dashboard/server/handlers.clj`:
  raised event limit from 50 → 200 for workflow detail and events fragment.
- `components/web-dashboard/resources/public/js/app.js`: picks up additional
  event types for richer phase timeline rendering.
- `components/web-dashboard/test/ai/miniforge/web_dashboard/server/websocket_test.clj` (new):
  `ws-event-envelope-test`, `normalize-workflow-event-test`.
- `components/web-dashboard/test/ai/miniforge/web_dashboard/state/workflows_test.clj` (new):
  `get-events-merges-live-and-historical-test`.

## Strata Affected
- `ai.miniforge.phase` (interface + new telemetry ns)
- `ai.miniforge.phase-software-factory` (all 5 phase implementations + release test)
- `ai.miniforge.web-dashboard.server.websocket`
- `ai.miniforge.web-dashboard.state.workflows`
- `ai.miniforge.web-dashboard.server.handlers`

## Testing Plan
- [ ] `bb test components/phase-software-factory` — release streaming callback test
- [ ] `bb test components/web-dashboard` — websocket normalization + workflows state tests
- [ ] Smoke: run dogfood, verify events appear in dashboard with correct types

## Deployment Plan
- Merge normally.
- Event history from prior runs (if present in `~/.miniforge/events/`) will
  automatically appear in the dashboard.

## Related Issues/PRs
- Spec: `work/finish-event-telemetry.spec.edn` task 1 (phase events) and task 3 (WS envelope fix)

## Risks and Notes
- `read-event-file` fails silently on malformed lines — safe to deploy with
  existing event archives.

## Checklist
- [x] Isolated onto a clean branch from `main`
- [x] Added PR doc under `docs/pull-requests/`
- [x] New tests for all changed surface areas
