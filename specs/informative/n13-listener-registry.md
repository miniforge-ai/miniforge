<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Listener Registry — Resume Signal Dispatcher Data Model

**Date:** 2026-05-07
**Status:** Informative (N13 supporting spec)
**Informs:** N13 §2.7, `pr-monitoring-workflow.md` Resume Signal Dispatcher

---

## Overview

The Closed-Loop PR Pipeline (N13) needs a registry of agents that have
declared they are waiting on a specific PR's merge. When the PR merges,
each registered listener receives a structured resume primer through its
declared channel.

This document specifies the registry schema, lifecycle, and the
canonical registration moments.

---

## Schema

```clojure
{:registry/version "0.1.0"

 ;; Listener entry — keyed by PR URL.
 :listener/entry
 {:listener/id          #uuid "..."          ; stable per registration
  :pr/url               "https://github.com/<org>/<repo>/pull/<n>"
  :pr/repo-id           "<org>/<repo>"
  :pr/number            123
  :agent/id             "<runtime-stable-agent-id>"
  :session/id           "<session-uuid>"     ; agent's current session, if any
  :runtime              :claude-cli|:codex|:miniforge|:webhook
  :resume-channel       {:channel/kind   :pty|:miniforge-ipc|:webhook
                         :channel/target "<pty-id|topic|url>"
                         :channel/auth   {...}}             ; optional, per-kind
  :registered-at        #inst "..."
  :registered-by        :authoring-agent|:operator|:workflow
  :ttl-seconds          604800                              ; default 7 days
  :status               :active|:dispatched|:expired|:cancelled
  :resume/dispatched-at #inst "..."                         ; nil until merge
  :resume/dispatch-id   #uuid "..."                         ; correlates to evidence
  :notes                "<optional human note>"}}
```

---

## Lifecycle

### States

```text
        register
   ─────────────────▶  :active
                         │
                         ├── pr.closed.merged ──▶ :dispatched
                         │
                         ├── ttl-elapsed ──────▶ :expired
                         │
                         └── unregister ───────▶ :cancelled
```

A listener entry MUST exist in exactly one state at a time. Transitions
are append-only events on the registry's event log; the current state
is a fold over those events.

### Registration moments

A listener MUST be registered at one of these moments:

1. **Authoring-agent emit** — when an agent emits a PR-open event via
   release phase (or equivalent), it MAY include a
   `:listener/register` clause naming itself as the listener. This is
   the default for Miniforge-authored PRs.
2. **Operator binding** — via the N11 Native Control Console, the
   operator binds a non-authoring agent (e.g., a parallel driving
   session) to a PR's merge.
3. **Workflow declaration** — a multi-step workflow that depends on a
   PR landing MAY register itself as a listener; the workflow's resume
   handler is invoked instead of an agent's.

Registration outside these three moments MUST be rejected.

### Deregistration

A listener SHOULD be deregistered when:

- The agent exits cleanly and is no longer waiting on the PR.
- The operator cancels the binding.
- The PR is closed without merging (registry MAY mark the listener
  `:cancelled` automatically on `pull_request.closed` with
  `merged: false`).

A listener MUST be auto-expired when `:ttl-seconds` elapses without
merge or dispatch.

---

## Storage

The registry is a Miniforge artifact (per N6) stored at:

```text
.miniforge/listener-registry.edn
```

Schema:

```clojure
{:registry/version "0.1.0"
 :registry/listeners {<pr-url-string> [<listener-entry> ...]}
 :registry/last-updated #inst "..."}
```

The `:registry/version` field tracks the on-disk artifact format and
moves on the same cadence as this spec document; both currently sit at
`0.1.0` (`-draft` in this document's footer denotes status, not a
distinct version).

Updates MUST be transactional (write-rename) to avoid partial reads.

### Multi-tenant note

For Miniforge Enterprise (per N12), the registry MAY be backed by a
shared store keyed by tenant. The single-tenant artifact path remains
the default.

---

## Dispatch

When `pull_request.closed.merged` arrives for `<pr-url>`:

1. Read all entries with `:status :active` for that PR URL.
2. For each, build the resume primer per N13 §2.7:

   ```clojure
   {:resume/pr-url      "..."
    :resume/merge-sha   "..."
    :resume/merged-at   #inst "..."
    :resume/diff-summary "..."
    :resume/listener    {:agent/id ... :session/id ...}}
   ```

3. Send via the listener's `:resume-channel`. Per-kind contracts:
   - `:pty` — write the primer (rendered as a structured prompt) to the
     PTY's input fd via the N11 Native Control Console.
   - `:miniforge-ipc` — emit the primer as a typed event on the topic
     declared in `:channel/target`; the agent's runtime subscribes to
     that topic.
   - `:webhook` — POST the primer JSON to `:channel/target` with
     optional auth from `:channel/auth`. Standard HTTP retry rules
     apply (3 attempts, exponential backoff).
4. On successful send, transition the entry to `:dispatched` and record
   `:resume/dispatched-at` + `:resume/dispatch-id`.
5. On failed send after retries, emit a `:listener/dispatch-failed`
   event and surface to N11 attention queue. Entry remains `:active`
   until the next attempt or operator action.

---

## Evidence

Each dispatch MUST append to the PR's evidence bundle (per N6):

```clojure
{:evidence/listener-dispatch
 {:dispatch/id        #uuid "..."
  :listener/id        #uuid "..."
  :pr/url             "..."
  :merge/sha          "..."
  :channel/kind       :pty|:miniforge-ipc|:webhook
  :dispatched-at      #inst "..."
  :outcome            :delivered|:failed
  :failure-reason     "..."}} ; nil on :delivered
```

---

## Operator surface (N11)

The N11 Native Control Console MUST surface:

- A "PR listeners" view per agent — which PRs the agent is waiting on,
  with current registry status and TTL remaining.
- A "Listeners on PR" view per PR — which agents are waiting, useful
  when triaging a stalled PR.
- A "Cancel binding" action per listener entry.
- An attention item on `:listener/dispatch-failed`.

---

## Conformance

A conforming Miniforge implementation MUST:

1. Implement the listener entry schema as specified.
2. Implement the four state transitions.
3. Persist the registry at `.miniforge/listener-registry.edn` (or the
   tenant-scoped equivalent for Enterprise).
4. Honor the three registration moments and reject other registrations.
5. Implement at least the `:pty` and `:miniforge-ipc` channels.
6. Append evidence per the §Evidence section.

A conforming implementation SHOULD:

- Surface registry state in N11 per §Operator surface.
- Auto-cancel listeners on `pull_request.closed` with `merged: false`.
- Auto-expire on TTL elapse.

A conforming implementation MAY:

- Add additional channel kinds beyond the three specified.
- Coalesce identical primers when multiple listeners share an
  `:agent/id` (rare).

---

**Version:** 0.1.0-draft
**Last Updated:** 2026-05-07
