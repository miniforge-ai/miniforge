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
(ns ai.miniforge.deliberation-workspace.run
  "The N14 Stage 0 run loop: schedule, project, activate, validate, commit,
   repeat until a closing rule fires.

   The activation function is injected. Every LLM call in a real run happens
   behind it, so the whole loop is exercised deterministically in tests with
   a function that returns canned transactions."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-workspace.commit :as commit]
   [ai.miniforge.deliberation-workspace.projection :as projection]
   [ai.miniforge.deliberation-workspace.scheduler :as scheduler]
   [ai.miniforge.deliberation-workspace.termination :as termination]
   [ai.miniforge.deliberation-workspace.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} spend [workspace]
  (update-in workspace [:workspace/spent :activations] (fnil inc 0)))

(defn- ^{:stratum 0} record-event [workspace event]
  (update workspace :workspace/events (fnil conj []) event))

(defn- ^{:stratum 0} last-seen
  "The workspace version at `role`'s previous activation — the `since` the
   projection's delta is computed from."
  [workspace role]
  (get-in workspace [:workspace/last-seen role] 0))

(defn- ^{:stratum 0} note-activation [workspace role]
  (assoc-in workspace [:workspace/last-seen role] (:workspace/version workspace)))

(defn- ^{:stratum 0} stages-of
  "The validation chain for this run.

   Defaulting to an empty chain would let a caller who forgot to populate
   :workspace/stages commit unknown operations, unknown targets, and stale
   writes without a single check running — silently, and looking healthy in
   the event log. The concurrency stages are the floor."
  [workspace]
  (let [stages (get workspace :workspace/stages)]
    (if (seq stages) stages validation/concurrency-stages)))

(defn- ^{:stratum 0} count-quiet
  "Track consecutive transactions that did not grow the object set, which
   is what the §7 quiescence rule measures. The measure is the size of
   `:workspace/objects`, not a scan for open objects: `new-object` gives
   every created object a non-terminal initial status, so a transaction
   grows the set exactly when it adds an object a role can still act on.
   Takes the committed workspace first so it threads after `commit`."
  [committed previous]
  (let [object-count (fn [ws] (count (get ws :workspace/objects {})))]
    (if (> (object-count committed) (object-count previous))
      (assoc committed :workspace/quiet-rounds 0)
      (update committed :workspace/quiet-rounds (fnil inc 0)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} step
  "Run one activation. Returns the workspace after the attempt, whether the
   transaction committed or was rejected — a rejection still costs budget,
   because the activation ran.

   `activate` receives {:role :projection :workspace} and returns a
   transaction, or nil to pass."
  [workspace activate]
  (if-let [{:activation/keys [role reason target]} (scheduler/next-activation workspace)]
    (let [visibility (get workspace :workspace/visibility :full)
          rendered (projection/project workspace role
                                       {:visibility visibility
                                        :since (last-seen workspace role)})
          proposed (activate {:role role :projection rendered :workspace workspace})
          spent (-> workspace spend (note-activation role)
                    (record-event {:event :activation/completed
                                   :role role :reason reason :target target}))]
      (if (nil? proposed)
        (record-event spent {:event :transaction/passed :role role})
        (if-let [rejection (validation/validate spent proposed (stages-of workspace))]
          (record-event spent {:event :transaction/rejected
                               :role role
                               :reason (anomaly/subtype rejection)})
          (-> (commit/commit spent proposed)
              (count-quiet spent)
              (record-event {:event :transaction/committed :role role})))))
    ;; Nothing eligible. Charging an activation that never ran would distort
    ;; the N15 cost accounting; the deadlock rule closes the run instead.
    (record-event workspace {:event :activation/none-eligible})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run
  "Drive the loop until a §7 closing rule fires, then return the closed
   workspace with its termination record attached.

   `max-steps` is a harness backstop, not a spec rule: budgets and closing
   rules should end a run first, and a run that hits this bound is a defect
   worth surfacing rather than a normal ending."
  [workspace activate {:keys [max-steps] :or {max-steps 1000}}]
  (loop [ws workspace
         steps 0]
    (if-let [closing (termination/closing-rule ws)]
      (assoc ws :workspace/termination closing)
      (if (>= steps max-steps)
        (assoc ws :workspace/termination {:termination/rule :step-bound-exceeded})
        (recur (step ws activate) (inc steps))))))
