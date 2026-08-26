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
(ns ai.miniforge.evidence-bundle.collectors
  "Gathering evidence from workflow state and the event stream: tool
   invocations, rules, policy checks, pack promotions, supervision
   decisions, control actions, and execution output."
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} build-rule-applied-entry
  "Normalize a manifest entry into the expected rule-applied shape.
   Ensures all fields have valid defaults and annotates with phase name."
  [entry phase-name]
  {:id (or (:id entry) (random-uuid))
   :title (get entry :title "unknown")
   :role (get entry :role :unknown)
   :tags-matched (vec (get entry :tags-matched []))
   :score (double (get entry :score 0.0))
   :phase (get entry :phase phase-name)})

;; Policy Check Evidence
(defn ^{:stratum 0} build-policy-check-evidence
  "Build policy check evidence from gate result.
   Returns policy check evidence per N6 spec."
  [gate-result]
  {:policy-check/pack-id (get gate-result :pack-id "unknown")
   :policy-check/pack-version (get gate-result :pack-version "1.0.0")
   :policy-check/phase (get gate-result :phase :unknown)
   :policy-check/checked-at (get gate-result :checked-at (java.time.Instant/now))
   :policy-check/violations (vec (get gate-result :violations []))
   :policy-check/passed? (get gate-result :passed? true)
   :policy-check/duration-ms (get gate-result :duration-ms 0)
   :policy-check/envelope (get gate-result :envelope)})

;; Pack Promotion Evidence
(defn ^{:stratum 0} build-pack-promotion-evidence
  "Build pack promotion evidence from promotion record.
   Returns pack promotion evidence per N6 section 2.1.

   If the promotion record already has the correct format (with :pack/id),
   return it as-is. Otherwise, build from legacy format."
  [promotion-record]
  ;; If already in correct format, return as-is
  (if (contains? promotion-record :pack/id)
    promotion-record
    ;; Otherwise, build from legacy format
    {:pack/id (get promotion-record :pack-id "unknown")
     :pack/type (get promotion-record :pack-type :knowledge)
     :from-trust (get promotion-record :from-trust :untrusted)
     :to-trust (get promotion-record :to-trust :trusted)
     :promoted-by (get promotion-record :promoted-by "system")
     :promoted-at (get promotion-record :promoted-at (java.time.Instant/now))
     :promotion-policy (get promotion-record :promotion-policy "knowledge-safety")
     :promotion-justification (get promotion-record :promotion-justification
                                   "No justification provided")
     :pack-hash (get promotion-record :pack-hash "")
     :pack-signature (get promotion-record :pack-signature "")}))

;------------------------------------------------------------------------------ Layer 3.5
;; Supervision Decision Evidence (N6)
(defn ^{:stratum 0} collect-event-stream-events
  [event-stream query]
  (try
    (when event-stream
      (vec (event-stream/get-events event-stream query)))
    (catch Exception _e
      [])))

;------------------------------------------------------------------------------ Layer 4.5
;; Execution Evidence (N11 §9.1)
(defn ^{:stratum 0} collect-tool-invocations
  "Collect tool invocation records from workflow state."
  [workflow-state]
  (vec (get workflow-state :workflow/tool-invocations [])))

;------------------------------------------------------------------------------ Layer 1.5
;; Rules Applied Evidence
(defn ^{:stratum 0} collect-execution-evidence
  "Extract N11 §9.1 execution evidence fields from workflow state.
   Looks in :execution/output for evidence fields produced by runner/extract-output.
   Returns a map of evidence keys to merge into the bundle, or empty map."
  [workflow-state]
  (let [output (get workflow-state :execution/output {})]
    (cond-> {}
      (contains? output :evidence/execution-mode)
      (assoc :evidence/execution-mode (:evidence/execution-mode output))

      (contains? output :evidence/runtime-class)
      (assoc :evidence/runtime-class (:evidence/runtime-class output))

      (contains? output :evidence/task-started-at)
      (assoc :evidence/task-started-at (:evidence/task-started-at output))

      (contains? output :evidence/task-finished-at)
      (assoc :evidence/task-finished-at (:evidence/task-finished-at output))

      (contains? output :evidence/image-digest)
      (assoc :evidence/image-digest (:evidence/image-digest output)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} collect-rules-applied
  "Collect rules-applied evidence from phase results.
   Each phase that captured a rules-manifest has its entries normalized
   and annotated with the phase name.
   Returns empty vector when no phases have a rules manifest."
  [workflow-state]
  (let [phases (get workflow-state :workflow/phases {})]
    (vec (mapcat (fn [[phase-name phase-data]]
                   (when-let [manifest (:rules-manifest phase-data)]
                     (mapv #(build-rule-applied-entry % phase-name) manifest)))
                 phases))))

(defn ^{:stratum 1} collect-policy-checks
  "Collect all policy check evidence from workflow state.
   Returns vector of policy check evidence."
  [workflow-state]
  (let [gate-results (get workflow-state :workflow/gate-results [])]
    (mapv build-policy-check-evidence gate-results)))

(defn ^{:stratum 1} collect-pack-promotions
  "Collect all pack promotion evidence from workflow state.
   Returns vector of pack promotion evidence."
  [workflow-state]
  (let [promotions (get workflow-state :workflow/pack-promotions [])]
    (mapv build-pack-promotion-evidence promotions)))

(defn ^{:stratum 1} collect-supervision-decisions
  "Collect supervision decision events from the event stream.

   Filters for :supervision/tool-use-evaluated events and transforms
   them into evidence records.

   Arguments:
   - event-stream: Event stream atom
   - workflow-id: UUID of the workflow

   Returns vector of supervision decision evidence maps."
  [event-stream workflow-id]
  (try
    (when event-stream
      (let [events (collect-event-stream-events event-stream
                                                {:workflow-id workflow-id
                                                 :event-type :supervision/tool-use-evaluated})]
        (mapv (fn [event]
                (cond-> {:supervision/tool-name (get event :tool/name "unknown")
                         :supervision/decision (get event :supervision/decision "allow")
                         :supervision/timestamp (get event :event/timestamp
                                                     (java.util.Date.))}
                  (:supervision/reasoning event)
                  (assoc :supervision/reasoning (:supervision/reasoning event))

                  (:supervision/meta-eval? event)
                  (assoc :supervision/meta-eval? true)

                  (:supervision/confidence event)
                  (assoc :supervision/confidence (:supervision/confidence event))

                  (:workflow/phase event)
                  (assoc :supervision/phase (:workflow/phase event))))
              events)))
    (catch Exception _e
      ;; event-stream dependency might not be loaded
      [])))

(defn ^{:stratum 1} collect-control-actions
  "Collect control action events from the event stream.

   Pairs :control-action/requested with :control-action/executed events.

   Arguments:
   - event-stream: Event stream atom
   - workflow-id: UUID of the workflow

   Returns vector of control action evidence maps."
  [event-stream workflow-id]
  (try
    (when event-stream
      (let [requested (collect-event-stream-events event-stream
                                                   {:workflow-id workflow-id
                                                    :event-type :control-action/requested})
            executed (collect-event-stream-events event-stream
                                                  {:workflow-id workflow-id
                                                   :event-type :control-action/executed})
            executed-by-id (into {} (map (fn [e] [(:action/id e) e]) executed))]
        (mapv (fn [req-event]
                (let [action-id (:action/id req-event)
                      exec-event (get executed-by-id action-id)]
                  (cond-> {:control-action/id action-id
                           :control-action/type (:action/type req-event)
                           :control-action/requester (get req-event :action/requester {})
                           :control-action/timestamp (get req-event :event/timestamp
                                                         (java.util.Date.))
                           :control-action/result (if exec-event :executed :pending)}
                    (:action/justification req-event)
                    (assoc :control-action/justification (:action/justification req-event))

                    (:action/target req-event)
                    (assoc :control-action/target (:action/target req-event)))))
              requested)))
    (catch Exception _e
      [])))

;; Outcome Evidence
