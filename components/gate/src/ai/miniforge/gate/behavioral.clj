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

(ns ai.miniforge.gate.behavioral
  "Behavioral verification gate.

   Evaluates event streams observed from behavioral harness runs against
   policy violations. Used in the monitor phase after a PR lands.

   - :behavioral - Check observed events for blocking or warning violations

   Violation sources (in priority order):
   1. :behavioral-violations in ctx — pre-computed vector (injected by monitor phase or tests)
   2. Derived on-the-fly from :events × :policy-packs via policy-pack component"
  (:require [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(def ^:private blocking-severities
  "Violation severities that cause a hard gate failure."
  #{:critical :error :blocking})

(def ^:private warning-severities
  "Violation severities that produce warnings but allow the gate to pass."
  #{:warning :medium :low :info})

(defn classify-violations
  "Partition violations into blocking and warning buckets.

   Arguments:
     violations - Vector of violation maps with :violation/severity keys

   Returns:
     {:blocking [...] :warnings [...]}"
  [violations]
  {:blocking (filterv #(blocking-severities (:violation/severity %)) violations)
   :warnings (filterv #(warning-severities  (:violation/severity %)) violations)})

(defn- violation->error
  "Convert a blocking violation map to a gate :errors entry."
  [violation]
  (cond-> {:type     :behavioral-violation
           :severity (:violation/severity violation)
           :message  (get violation :violation/message "Behavioral violation detected")}
    (:violation/rule-id violation) (assoc :rule-id (:violation/rule-id violation))))

(defn- violation->warning
  "Convert a warning violation map to a gate :warnings entry."
  [violation]
  (cond-> {:type     :behavioral-warning
           :severity (:violation/severity violation)
           :message  (get violation :violation/message "Behavioral warning")}
    (:violation/rule-id violation) (assoc :rule-id (:violation/rule-id violation))))

(defn- derive-violations
  "Attempt to derive violations from events × policy packs via the policy-pack component.
   Returns an empty vector when the component is unavailable or check fails."
  [events packs]
  (try
    (when-let [check-fn (requiring-resolve
                          'ai.miniforge.policy-pack.core/check-behavioral-events)]
      (vec (get (check-fn packs events {}) :violations [])))
    (catch Exception _
      [])))

;------------------------------------------------------------------------------ Layer 1
;; Gate implementation

(defn check-behavioral
  "Check behavioral event stream for policy violations.

   Arguments:
     artifact - Map with :events key (vector of event maps from harness run)
     ctx      - Execution context:
                :policy-packs          — vector of loaded policy pack maps
                :behavioral-violations — pre-computed violation vector (takes priority)

   Returns:
     {:passed? bool :errors [...] :warnings [...]}"
  [artifact ctx]
  (let [events (get artifact :events [])
        packs  (get ctx :policy-packs [])]
    (cond
      ;; Nothing to evaluate — no harness, no packs, no pre-computed violations
      (and (empty? events)
           (empty? packs)
           (not (contains? ctx :behavioral-violations)))
      {:passed?  true
       :warnings [{:type    :no-behavioral-harness
                   :message "No events observed and no policy packs loaded; behavioral verification skipped"}]}

      :else
      (let [violations (if (contains? ctx :behavioral-violations)
                         (get ctx :behavioral-violations)
                         (derive-violations events packs))
            {:keys [blocking warnings]} (classify-violations (or violations []))]
        (if (seq blocking)
          {:passed?  false
           :errors   (mapv violation->error blocking)
           :warnings (mapv violation->warning warnings)}
          {:passed?  true
           :warnings (mapv violation->warning warnings)})))))

(defn repair-behavioral
  "Attempt to repair behavioral violations.

   Always returns failure — behavioral repair requires human or LLM intervention.

   Arguments:
     artifact - Artifact being checked
     errors   - Errors from check-behavioral
     _ctx     - Execution context (unused)

   Returns:
     {:success? false :artifact artifact :errors errors :message string}"
  [artifact errors _ctx]
  {:success? false
   :artifact artifact
   :errors   errors
   :message  "Behavioral violation repair requires human intervention"})

;------------------------------------------------------------------------------ Layer 1
;; Registry

(registry/register-gate! :behavioral)

(defmethod registry/get-gate :behavioral
  [_]
  {:name        :behavioral
   :description "Validates behavioral harness event stream against policy violations"
   :check       check-behavioral
   :repair      repair-behavioral})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; No events, no packs → passes with :no-behavioral-harness warning
  (check-behavioral {} {})

  ;; Pre-computed blocking violation → gate fails
  (check-behavioral
    {:events [{:event/type :agent/started}]}
    {:behavioral-violations [{:violation/severity :critical
                              :violation/rule-id  :no-crash
                              :violation/message  "Agent crashed unexpectedly"}]})

  ;; Pre-computed warning violation → gate passes with warnings
  (check-behavioral
    {:events [{:event/type :agent/started}]}
    {:behavioral-violations [{:violation/severity :warning
                              :violation/rule-id  :slow-phase
                              :violation/message  "Phase took longer than expected"}]})

  ;; Empty pre-computed violations → clean pass
  (check-behavioral
    {:events [{:event/type :agent/completed :event/data {:exit-code 0}}]}
    {:behavioral-violations []})

  :leave-this-here)
