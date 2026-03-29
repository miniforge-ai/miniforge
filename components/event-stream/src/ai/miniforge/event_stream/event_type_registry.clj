;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.event-stream.event-type-registry
  "Authoritative registry of all server-side event types and their browser coverage.

   This namespace is the single source of truth for the event-type naming audit
   (Tasks 1–7).  It documents:

     1.  Every constructor defined in `interface/events.clj`
     2.  The `:event/type` keyword each constructor places in the envelope
         (see `event-stream.core/create-envelope`)
     3.  The JSON string that keyword serialises to when transmitted over
         WebSocket / SSE (Cheshire / jsonista: keyword → string, colon stripped,
         namespace and name joined with \"/\")
     4.  Whether the browser `handleWorkflowEvent` switch in `app.js` handles
         that string
     5.  The constructor-name → serialised-string naming asymmetries that
         would trip up a developer reading only `interface/events.clj`

   ## Audit verdict (2026-03-28)

   * NO mismatches: every browser `case` string exactly matches the server keyword.
   * LARGE coverage gap: only 6 of 43 server-side event types are handled in
     the browser switch; the remaining 37 silently fall through to `default: break`.
   * NAMING ASYMMETRIES: 13 constructors use a function name whose implied
     namespace differs from the actual `:event/type` namespace.  See
     `naming-asymmetries` below.")

;------------------------------------------------------------------------------ Layer 0
;; Registry

(def event-type-registry
  "Complete mapping from constructor symbol name (as string) to serialised
   JSON event-type string, grouped by originating namespace.

   Columns:
     :constructor  — var name in `interface/events.clj` / `core.clj`
     :event-type   — Clojure keyword set on `:event/type`
     :json-string  — string the browser receives in `event['event/type']`
     :browser?     — true iff `handleWorkflowEvent` in `app.js` has a case"
  [{:constructor "workflow-started"
    :event-type  :workflow/started
    :json-string "workflow/started"
    :browser?    true}

   {:constructor "phase-started"
    :event-type  :workflow/phase-started
    :json-string "workflow/phase-started"
    :browser?    true}

   {:constructor "phase-completed"
    :event-type  :workflow/phase-completed
    :json-string "workflow/phase-completed"
    :browser?    true}

   {:constructor "workflow-completed"
    :event-type  :workflow/completed
    :json-string "workflow/completed"
    :browser?    true}

   {:constructor "workflow-failed"
    :event-type  :workflow/failed
    :json-string "workflow/failed"
    :browser?    true}

   {:constructor "agent-chunk"
    :event-type  :agent/chunk
    :json-string "agent/chunk"
    :browser?    true
    :note        "High-frequency streaming event; browser intentionally does not toast but does refresh."}

   ;; ── Events with no browser handler ──────────────────────────────────────

   {:constructor "agent-status"
    :event-type  :agent/status
    :json-string "agent/status"
    :browser?    false}

   {:constructor "llm-request"
    :event-type  :llm/request
    :json-string "llm/request"
    :browser?    false}

   {:constructor "llm-response"
    :event-type  :llm/response
    :json-string "llm/response"
    :browser?    false}

   {:constructor "agent-started"
    :event-type  :agent/started
    :json-string "agent/started"
    :browser?    false}

   {:constructor "agent-completed"
    :event-type  :agent/completed
    :json-string "agent/completed"
    :browser?    false}

   {:constructor "agent-failed"
    :event-type  :agent/failed
    :json-string "agent/failed"
    :browser?    false}

   {:constructor "gate-started"
    :event-type  :gate/started
    :json-string "gate/started"
    :browser?    false}

   {:constructor "gate-passed"
    :event-type  :gate/passed
    :json-string "gate/passed"
    :browser?    false}

   {:constructor "gate-failed"
    :event-type  :gate/failed
    :json-string "gate/failed"
    :browser?    false}

   {:constructor "tool-invoked"
    :event-type  :tool/invoked
    :json-string "tool/invoked"
    :browser?    false}

   {:constructor "tool-completed"
    :event-type  :tool/completed
    :json-string "tool/completed"
    :browser?    false}

   {:constructor "milestone-reached"
    :event-type  :workflow/milestone-reached
    :json-string "workflow/milestone-reached"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor name implies namespace 'milestone'; actual namespace is 'workflow'"}

   {:constructor "task-state-changed"
    :event-type  :task/state-changed
    :json-string "task/state-changed"
    :browser?    false}

   {:constructor "task-frontier-entered"
    :event-type  :task/frontier-entered
    :json-string "task/frontier-entered"
    :browser?    false}

   {:constructor "task-skip-propagated"
    :event-type  :task/skip-propagated
    :json-string "task/skip-propagated"
    :browser?    false}

   {:constructor "inter-agent-message-sent"
    :event-type  :agent/message-sent
    :json-string "agent/message-sent"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor name implies namespace 'inter-agent'; actual namespace is 'agent'"}

   {:constructor "inter-agent-message-received"
    :event-type  :agent/message-received
    :json-string "agent/message-received"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor name implies namespace 'inter-agent'; actual namespace is 'agent'"}

   {:constructor "listener-attached"
    :event-type  :listener/attached
    :json-string "listener/attached"
    :browser?    false}

   {:constructor "listener-detached"
    :event-type  :listener/detached
    :json-string "listener/detached"
    :browser?    false}

   {:constructor "annotation-created"
    :event-type  :annotation/created
    :json-string "annotation/created"
    :browser?    false}

   {:constructor "control-action-requested"
    :event-type  :control-action/requested
    :json-string "control-action/requested"
    :browser?    false}

   {:constructor "control-action-executed"
    :event-type  :control-action/executed
    :json-string "control-action/executed"
    :browser?    false}

   {:constructor "chain-started"
    :event-type  :chain/started
    :json-string "chain/started"
    :browser?    false}

   {:constructor "chain-step-started"
    :event-type  :chain/step-started
    :json-string "chain/step-started"
    :browser?    false}

   {:constructor "chain-step-completed"
    :event-type  :chain/step-completed
    :json-string "chain/step-completed"
    :browser?    false}

   {:constructor "chain-step-failed"
    :event-type  :chain/step-failed
    :json-string "chain/step-failed"
    :browser?    false}

   {:constructor "chain-completed"
    :event-type  :chain/completed
    :json-string "chain/completed"
    :browser?    false}

   {:constructor "chain-failed"
    :event-type  :chain/failed
    :json-string "chain/failed"
    :browser?    false}

   ;; ── Layer 1: OCI container events ────────────────────────────────────────

   {:constructor "container-started"
    :event-type  :oci/container-started
    :json-string "oci/container-started"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor name implies namespace 'container'; actual namespace is 'oci'"}

   {:constructor "container-completed"
    :event-type  :oci/container-completed
    :json-string "oci/container-completed"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor name implies namespace 'container'; actual namespace is 'oci'"}

   ;; ── Layer 1: Tool supervision events ─────────────────────────────────────

   {:constructor "tool-use-evaluated"
    :event-type  :supervision/tool-use-evaluated
    :json-string "supervision/tool-use-evaluated"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor name implies namespace 'tool'; actual namespace is 'supervision'"}

   ;; ── Layer 1: Control plane events ────────────────────────────────────────

   {:constructor "cp-agent-registered"
    :event-type  :control-plane/agent-registered
    :json-string "control-plane/agent-registered"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor prefix 'cp-' expands to full namespace 'control-plane'"}

   {:constructor "cp-agent-heartbeat"
    :event-type  :control-plane/agent-heartbeat
    :json-string "control-plane/agent-heartbeat"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor prefix 'cp-' expands to full namespace 'control-plane'"}

   {:constructor "cp-agent-state-changed"
    :event-type  :control-plane/agent-state-changed
    :json-string "control-plane/agent-state-changed"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor prefix 'cp-' expands to full namespace 'control-plane'"}

   {:constructor "cp-decision-created"
    :event-type  :control-plane/decision-created
    :json-string "control-plane/decision-created"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor prefix 'cp-' expands to full namespace 'control-plane'"}

   {:constructor "cp-decision-resolved"
    :event-type  :control-plane/decision-resolved
    :json-string "control-plane/decision-resolved"
    :browser?    false
    :asymmetry?  true
    :asymmetry-note "Constructor prefix 'cp-' expands to full namespace 'control-plane'"}])

;------------------------------------------------------------------------------ Layer 0
;; Derived views

(def browser-handled-events
  "The 6 event types currently handled in `handleWorkflowEvent` in app.js.
   All strings confirmed correct — no mismatches."
  (->> event-type-registry
       (filter :browser?)
       (mapv :json-string)))
;; => ["workflow/started" "workflow/phase-started" "workflow/phase-completed"
;;     "workflow/completed" "workflow/failed" "agent/chunk"]

(def browser-unhandled-events
  "37 event types emitted server-side that the browser switch silently ignores.
   These are the gap items for Tasks 1–7."
  (->> event-type-registry
       (remove :browser?)
       (mapv :json-string)))

(def naming-asymmetries
  "13 constructors whose function name does not predict the namespace portion
   of the serialised event-type string.  A developer reading only
   `interface/events.clj` would guess the wrong browser case string.

   Format: [constructor → json-string (note)]"
  (->> event-type-registry
       (filter :asymmetry?)
       (mapv (fn [{:keys [constructor json-string asymmetry-note]}]
               {:constructor    constructor
                :json-string    json-string
                :asymmetry-note asymmetry-note}))))
;; Asymmetries at a glance:
;;
;;   milestone-reached          → "workflow/milestone-reached"   (namespace: milestone → workflow)
;;   inter-agent-message-sent   → "agent/message-sent"           (namespace: inter-agent → agent)
;;   inter-agent-message-received → "agent/message-received"     (namespace: inter-agent → agent)
;;   container-started          → "oci/container-started"        (namespace: container → oci)
;;   container-completed        → "oci/container-completed"      (namespace: container → oci)
;;   tool-use-evaluated         → "supervision/tool-use-evaluated" (namespace: tool → supervision)
;;   cp-agent-registered        → "control-plane/agent-registered"  (prefix cp → control-plane)
;;   cp-agent-heartbeat         → "control-plane/agent-heartbeat"   (prefix cp → control-plane)
;;   cp-agent-state-changed     → "control-plane/agent-state-changed" (prefix cp → control-plane)
;;   cp-decision-created        → "control-plane/decision-created"  (prefix cp → control-plane)
;;   cp-decision-resolved       → "control-plane/decision-resolved" (prefix cp → control-plane)

;------------------------------------------------------------------------------ Layer 0
;; Audit summary (machine-readable)

(def audit-summary
  {:audit/date          "2026-03-28"
   :audit/source-server "components/event-stream/src/ai/miniforge/event_stream/interface/events.clj"
   :audit/source-browser "components/web-dashboard/resources/public/js/app.js"
   :audit/browser-switch "handleWorkflowEvent"

   :total-server-events      43
   :browser-handled-count    6
   :browser-unhandled-count  37
   :naming-asymmetry-count   11

   ;; Verdict
   :string-mismatches []
   ;; ^ NONE: every browser case string exactly matches a server-emitted value.
   ;; The 6 handled events are a correct, strict subset of server events.

   :coverage-gaps browser-unhandled-events
   ;; ^ 37 events silently ignored by the browser. Adding cases for these
   ;;   is the primary work of Tasks 1–7.

   :asymmetries naming-asymmetries
   ;; ^ When adding browser cases, use the :json-string column above,
   ;;   NOT a mechanical transformation of the constructor name.

   :serialisation-rule
   "Clojure namespaced keyword :ns/name serialises (Cheshire/jsonista) to
    the plain string \"ns/name\" — no leading colon.  The browser reads
    event['event/type'] (key has a literal slash) and compares against these
    plain strings."})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Quick queries for Tasks 1–7

  ;; Which events are unhandled?
  browser-unhandled-events

  ;; Which constructors have surprising namespace prefixes?
  (map (juxt :constructor :json-string) naming-asymmetries)

  ;; Check a specific constructor's browser string
  (->> event-type-registry
       (filter #(= "inter-agent-message-sent" (:constructor %)))
       first
       :json-string)
  ;; => "agent/message-sent"

  ;; All events grouped by namespace prefix
  (->> event-type-registry
       (group-by #(-> % :json-string (clojure.string/split #"/") first))
       (into (sorted-map))
       (map (fn [[ns evts]] [ns (mapv :json-string evts)]))
       (into (sorted-map)))

  :leave-this-here)
