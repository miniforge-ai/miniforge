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
(ns ai.miniforge.phase.registry
  "Phase interceptor registry using multimethods.

   Phases register via defmethod, providing:
   - Default configuration (agent, gates, budget)
   - Interceptor factory function

   Pattern borrowed from Ixi's interceptors-pedestal component."
  (:require [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

;; Schemas
(def ^{:stratum 0} Budget
  "Budget configuration schema"
  [:map
   [:tokens {:optional true} pos-int?]
   [:iterations {:optional true} pos-int?]
   [:time-seconds {:optional true} pos-int?]])

(def ^{:stratum 0} Interceptor
  "Interceptor schema (Pedestal-style)"
  [:map
   [:name keyword?]
   [:enter {:optional true} fn?]
   [:leave {:optional true} fn?]
   [:error {:optional true} fn?]])

;; Defaults registry (atom for extensibility)
(defonce ^{:stratum 0} defaults-registry (atom {}))

(def ^{:stratum 0} policy-gates
  "Governance gates a phase-config override must never drop from the phase-type
   default. A workflow's per-phase `:gates` may add or trim mechanical gates
   (syntax, lint, coverage, …) freely, but silently losing policy enforcement is
   the one merge outcome a governance system cannot allow: the shallow
   `(merge defaults config)` let a workflow that declared `:gates [...]` at
   verify/review replace the default vector wholesale, dropping
   `:policy-verify`/`:policy-review` with no signal (e.g. quick-fix-v2's verify).
   `merge-with-defaults` re-adds any of these the default enforced but the
   override omitted. Extend this set when a new governance gate ships.

   :codex-consultation is governance, not mechanics: it asserts delivery
   provenance for the codex warning surface, and the canonical-sdlc v2
   implement `:gates` override was silently dropping it — exactly the
   footgun this set exists to close."
  #{:policy-verify :policy-review :policy-pack :codex-consultation})

;; Phase status predicates
;;
;; Accept either a bare keyword (:completed, :failed, etc.) or a map with
;; a status key (:status, :execution/status, :step/status, :chain/status).
(def ^{:stratum 0} already-done-statuses
  "Statuses indicating work is already complete — neutral outcome."
  #{:already-satisfied :already-implemented})

(def ^{:stratum 0} status-keys
  "Keys to try when extracting status from a map, in priority order."
  [:status :execution/status :step/status :chain/status])

(def ^{:stratum 0} ^:private inner-failure-statuses
  #{:error :failed :failure})

(defn- ^{:stratum 0} result-status
  [m]
  (get-in m [:result :status]))

(defn ^{:stratum 0} determine-phase-status
  "Determine phase status from agent result status, iteration count, and budget.

   Arguments:
   - agent-status: The :status from the agent result (:success, :error, etc.)
   - iterations: Current iteration count
   - max-iterations: Maximum allowed iterations

   Returns: :completed, :retrying, or :failed"
  [agent-status iterations max-iterations]
  (cond
    (= :success agent-status) :completed
    (and (= :error agent-status)
         (< iterations max-iterations)) :retrying
    (= :error agent-status) :failed
    (not= :success agent-status) :failed
    :else :completed))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} PhaseConfig
  "Phase configuration schema"
  [:map
   [:phase keyword?]
   [:agent {:optional true} keyword?]
   [:gates {:optional true} [:vector keyword?]]
   [:budget {:optional true} Budget]
   [:on-fail {:optional true} keyword?]
   [:on-success {:optional true} keyword?]
   [:metadata {:optional true} map?]
   ;; Model selection hints
   [:model-hint {:optional true} keyword?]
   [:require-model {:optional true} keyword?]])

;; Multimethod registry
(defmulti ^{:stratum 1} get-phase-interceptor
  "Get interceptor for a phase configuration.

   Dispatches on :phase keyword.

   Arguments:
     config - Map with :phase and optional overrides

   Returns:
     Interceptor map with :name, :enter, :leave, :error"
  :phase)

(defmethod ^{:stratum 1} get-phase-interceptor :default
  [{:keys [phase]}]
  (throw (ex-info (str "Unknown phase type: " phase
                       ". Available: " (keys @defaults-registry))
                  {:phase phase
                   :available (keys @defaults-registry)})))

;; Registry management
(defn ^{:stratum 1} register-phase-defaults!
  "Register default configuration for a phase type.

   Arguments:
     phase-kw - Phase keyword like :plan, :implement
     defaults - Default config map {:agent :gates :budget}"
  [phase-kw defaults]
  (swap! defaults-registry assoc phase-kw defaults))

(defn ^{:stratum 1} phase-defaults
  "Get default configuration for a phase type.

   Arguments:
     phase-kw - Phase keyword

   Returns:
     Default config map or nil"
  [phase-kw]
  (get @defaults-registry phase-kw))

(defn ^{:stratum 1} list-phases
  "List all registered phase types.

   Returns:
     Set of phase keywords"
  []
  (set (keys @defaults-registry)))

(defn ^{:stratum 1} merge-gates
  "Merge an override `:gates` vector over the phase-type default's. The result is
   the override's gates in declared order, followed by any policy gate (see
   `policy-gates`) the default enforced that the override dropped — so an
   override can extend or trim mechanical gates but cannot disable policy
   enforcement."
  [default-gates override-gates]
  (let [override-set (set override-gates)
        preserved    (filterv #(and (contains? policy-gates %)
                                     (not (contains? override-set %)))
                              default-gates)]
    (into (vec override-gates) preserved)))

(defn ^{:stratum 1} valid-interceptor?
  "Check if interceptor is valid against schema."
  [interceptor]
  (m/validate Interceptor interceptor))

(defn- ^{:stratum 1} outer-status
  [m]
  (when m (some m status-keys)))

(defn- ^{:stratum 1} failure-status
  [m]
  (let [status (result-status m)]
    (when (contains? inner-failure-statuses status)
      :failed)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} merge-with-defaults
  "Merge user config with phase defaults.

   Shallow-merges `config` over the phase-type defaults, with one exception for
   `:gates`: a config `:gates` override replaces the default vector, EXCEPT that
   policy gates (`policy-gates`) the default enforced are re-added if the override
   dropped them. This closes the silent-override footgun where a workflow
   declaring `:gates [...]` at verify/review dropped `:policy-verify`/
   `:policy-review` wholesale.

   Arguments:
     config - User-provided phase config

   Returns:
     Merged config with defaults"
  [{:keys [phase] :as config}]
  (let [defaults (phase-defaults phase)
        merged   (merge defaults config)]
    (if (contains? config :gates)
      (assoc merged :gates (merge-gates (:gates defaults) (:gates config)))
      merged)))

(defn ^{:stratum 2} valid-config?
  "Check if phase config is valid against schema."
  [config]
  (m/validate PhaseConfig config))

(defn- ^{:stratum 2} completed-with-inner-failure?
  [m]
  (and (= :completed (outer-status m))
       (failure-status m)))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} extract-status
  "Extract a normalized phase status keyword from a map.

   Inner agent failures take precedence over an outer interceptor status of
   :completed so the workflow machine cannot advance on agent errors."
  [m]
  (let [status (outer-status m)]
    (if (completed-with-inner-failure? m)
      :failed
      status)))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} succeeded?
  "Check if a result map indicates success.
   Looks for :status, :execution/status, :step/status, or :chain/status."
  [m]
  (= :completed (extract-status m)))

(defn ^{:stratum 4} already-done?
  "Check if a result map indicates work was already complete.
   This is a neutral outcome — not a failure."
  [m]
  (contains? already-done-statuses (extract-status m)))

(defn ^{:stratum 4} succeeded-or-done?
  "Check if a result map indicates success or already-done (neutral).
   Use this for event emission and outcome reporting."
  [m]
  (let [s (extract-status m)]
    (or (= :completed s)
        (contains? already-done-statuses s))))

(defn ^{:stratum 4} failed?
  "Check if a result map indicates failure."
  [m]
  (= :failed (extract-status m)))

(defn ^{:stratum 4} retrying?
  "Check if a result map indicates retry."
  [m]
  (= :retrying (extract-status m)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Register a phase
  (register-phase-defaults! :test
                            {:agent :tester
                             :gates [:tests-pass]
                             :budget {:tokens 5000}})

  ;; Check defaults
  (phase-defaults :test)

  ;; List phases
  (list-phases)

  ;; Merge with defaults
  (merge-with-defaults {:phase :test :budget {:tokens 10000}})

  :leave-this-here)
