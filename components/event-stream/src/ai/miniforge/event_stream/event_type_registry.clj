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
   * LARGE coverage gap: only 6 of 45 server-side event types are handled in
     the browser switch; the remaining 39 silently fall through to `default: break`.
   * NAMING ASYMMETRIES: 13 constructors use a function name whose implied
     namespace differs from the actual `:event/type` namespace.  See
     `naming-asymmetries` below.

   Registry data lives in
   `resources/config/event-stream/event-type-registry.edn` — edit that
   file to add or modify event types.  This namespace loads it and
   derives the computed views below."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Registry — loaded from EDN resource

(def ^:private registry-resource-path
  "config/event-stream/event-type-registry.edn")

(def event-type-registry
  "Complete mapping from constructor symbol name (as string) to serialised
   JSON event-type string, grouped by originating namespace.

   Loaded from `resources/config/event-type-registry.edn` at namespace
   init. Edit that file to add or modify event types — do not inline
   data here.

   Columns:
     :constructor  — var name in `interface/events.clj` / `core.clj`
     :event-type   — Clojure keyword set on `:event/type`
     :json-string  — string the browser receives in `event['event/type']`
     :browser?     — true iff `handleWorkflowEvent` in `app.js` has a case"
  (-> (io/resource registry-resource-path)
      slurp
      edn/read-string))

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
  "Event types emitted server-side that the browser switch silently ignores.
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
  {:audit/date          "2026-05-24"
   :audit/source-server "components/event-stream/src/ai/miniforge/event_stream/interface/events.clj"
   :audit/source-browser "components/web-dashboard/resources/public/js/app.js"
   :audit/browser-switch "handleWorkflowEvent"

   :total-server-events      (count event-type-registry)
   :browser-handled-count    (count browser-handled-events)
   :browser-unhandled-count  (count browser-unhandled-events)
   :naming-asymmetry-count   (count naming-asymmetries)

   ;; Verdict
   :string-mismatches []
   ;; ^ NONE: every browser case string exactly matches a server-emitted value.
   ;; The 6 handled events are a correct, strict subset of server events.

   :coverage-gaps browser-unhandled-events
   ;; ^ Events silently ignored by the browser. Adding cases for these
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
