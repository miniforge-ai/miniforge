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

(ns ai.miniforge.workbench-orchestration-adapter.evaluation
  "Pure orchestration state-variable evaluation and evaluator provenance.

   Every evaluator is a deterministic function of the supervisory-state
   EntityTable plus caller-supplied context (the legal phase-transition map
   and the observed phase history). No LLM, no clock, no I/O."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.workbench-orchestration-adapter.config :as config]))

;------------------------------------------------------------------------------ Layer 0
;; Evaluation policy

(def ^:private product "miniforge")
(def ^:private evaluator-version "miniforge-orchestration-workbench-adapter/1.0.0")
(def ^:private policy-version "miniforge-orchestration-evaluation-policy/1.0.0")
(def ^:private rate-warn-threshold 0.8)

(def ^:private settled-intervention-states
  "InterventionRequest lifecycle states that need no further supervisory
   action per N5-delta-supervisory-control-plane §3.3."
  #{:rejected :applied :verified :failed})

(def ^:private evaluator-definition
  {:evaluators
   [{:id "miniforge.workflow.machine_authoritative" :formula :observed-history-subset-of-legal-transitions}
    {:id "miniforge.workflow.runs_resolved" :formula :resolved-runs-over-all-runs}
    {:id "miniforge.gate.critical_violations_block" :formula :critical-evaluations-all-blocked}
    {:id "miniforge.policy.evaluations_recorded" :formula :verify-traversal-implies-policy-evaluation}
    {:id "miniforge.attention.items_actioned" :formula :resolved-items-over-all-items}
    {:id "miniforge.decision.queue_drained" :formula :settled-queue-entries-over-all-entries}]})

(def ^:private policy-definition
  {:rate-warn-threshold rate-warn-threshold
   :terminal-statuses config/terminal-statuses
   :settled-intervention-states settled-intervention-states})

(def ^:private evaluator-hash
  (str "sha256:" (content-hash/content-hash evaluator-definition)))

(def ^:private policy-hash
  (str "sha256:" (content-hash/content-hash policy-definition)))

(def ^:private illegal-transition-message
  "Observed phase history contains a transition outside the authoritative machine")
(def ^:private terminal-phase-message
  "Completed run did not end on the machine's terminal phase")
(def ^:private dangling-runs-message
  "One or more workflow runs have not reached the resolved-run boundary")
(def ^:private unblocked-critical-message
  "One or more critical policy violations did not block progression")
(def ^:private missing-evaluation-message
  "Run traversed the verify phase without a recorded policy evaluation")
(def ^:private open-attention-message
  "One or more attention items remain unresolved at the boundary")
(def ^:private pending-queue-message
  "One or more decisions or interventions remain pending at the boundary")

(defn- ratio [numerator denominator]
  (if (zero? denominator)
    1.0
    (double (/ numerator denominator))))

(defn- status-for-boolean [ok?]
  (if ok? "pass" "fail"))

(defn- status-for-rate [score]
  (cond
    (= 1.0 score) "pass"
    (>= score rate-warn-threshold) "warn"
    :else "fail"))

(defn- gate-effect [status failure-effect]
  (case status
    "pass" "none"
    "not_applicable" "none"
    "warn" "marks_low_confidence"
    failure-effect))

(defn- finding [severity message]
  {:severity severity :message message})

(defn- rate-findings [status message]
  (case status
    "pass" nil
    "warn" [(finding "warn" message)]
    [(finding "error" message)]))

;------------------------------------------------------------------------------ Layer 1
;; Evidence and evaluation assembly

(defn- evaluation
  [{:keys [state-var-id status value score confidence components evidence findings
           gate-effect evaluated-at]}]
  (cond-> {:state_var_id state-var-id
           :product product
           :status status
           :score score
           :confidence confidence
           :evidence_refs (vec evidence)
           :findings (vec findings)
           :gate_effect gate-effect
           :evaluated_at evaluated-at}
    (some? value)      (assoc :value value)
    (some? components) (assoc :score_components components)))

(defn- run-evidence [run evaluated-at]
  [{:id (str "miniforge.workflow.run." (:workflow-run/id run))
    :source_role "workflow-run"
    :quote (str (:workflow-run/workflow-key run)
                " status " (name (:workflow-run/status run)))
    :created_at evaluated-at}])

(defn- absence-evidence
  "An honest evidence ref for an observed ABSENCE. A registry
   `required_refs` role must be represented even when the collection it is
   derived from is empty - otherwise the evaluation violates its own
   `evidence_requirements` (the workbench kernel's evidence validator
   matches `required_refs` against `source_role`). The quote states the
   absence the evaluator actually judged on; it never asserts a positive
   fact that was not observed."
  [{:keys [scope-id source-role quote evaluated-at]}]
  [{:id (str "miniforge.absent." source-role "." scope-id)
    :source_role source-role
    :quote quote
    :created_at evaluated-at}])

(defn- or-absence
  "`refs` when non-empty, otherwise a single honest absence ref."
  [refs absence]
  (if (seq refs) (vec refs) (absence-evidence absence)))

(defn- phase-history-evidence
  "One ref per observed transition. A resolved run can legally have fewer
   than two observed phases - failed/cancelled are lifecycle-terminal from
   any phase (N2 §2.3) - so with no transition pairs this emits a single
   honest ref describing the observed history instead of nothing."
  [run observed pairs evaluated-at]
  (or-absence
   (mapv (fn [index [from to]]
           {:id (str "miniforge.workflow.transition." (:workflow-run/id run) "." index)
            :source_role "workflow-phase-history"
            :quote (str (name from) " -> " (name to))
            :created_at evaluated-at})
         (range)
         pairs)
   {:scope-id (:workflow-run/id run)
    :source-role "workflow-phase-history"
    :quote (if (seq observed)
             (str "single observed phase " (name (first observed))
                  ", no transitions")
             "no phase-started events observed")
    :evaluated-at evaluated-at}))

(defn- table-run-evidence [runs evaluated-at]
  (mapv (fn [run]
          {:id (str "miniforge.workflow.run." (:workflow-run/id run))
           :source_role "workflow-run"
           :quote (str (:workflow-run/workflow-key run)
                       " status " (name (:workflow-run/status run)))
           :created_at evaluated-at})
        runs))

(defn- policy-eval-evidence [evals evaluated-at]
  (mapv (fn [policy-eval]
          {:id (str (:policy-eval/id policy-eval))
           :source_role "policy-evaluation"
           :quote (str "gate " (some-> policy-eval :policy-eval/gate-id name)
                       " passed? " (:policy-eval/passed? policy-eval)
                       ", violations " (count (:policy-eval/violations policy-eval)))
           :created_at evaluated-at})
        evals))

(defn- attention-evidence [items evaluated-at]
  (mapv (fn [item]
          {:id (str (:attention/id item))
           :source_role "attention-item"
           :quote (str (name (:attention/severity item)) " "
                       (:attention/summary item)
                       (if (:attention/resolved? item) " [resolved]" " [open]"))
           :created_at evaluated-at})
        items))

(defn- decision-evidence [decisions evaluated-at]
  (mapv (fn [decision]
          {:id (str (:decision/id decision))
           :source_role "decision-card"
           :quote (str (:decision/summary decision)
                       " status " (name (:decision/status decision)))
           :created_at evaluated-at})
        decisions))

(defn- intervention-evidence [interventions evaluated-at]
  (mapv (fn [intervention]
          {:id (str (:intervention/id intervention))
           :source_role "intervention-request"
           :quote (str (name (:intervention/type intervention))
                       " state " (name (:intervention/state intervention)))
           :created_at evaluated-at})
        interventions))

(defn- run-policy-evals [table run]
  (->> (vals (:policy-evals table))
       (filter #(= (:workflow-run/id run) (:policy-eval/workflow-run-id %)))
       (sort-by :policy-eval/evaluated-at)
       vec))

(defn- critical-policy-eval? [policy-eval]
  (boolean (some #(= :critical (:violation/severity %))
                 (:policy-eval/violations policy-eval))))

(defn- run-attention-items [table run]
  (->> (vals (:attention table))
       (filter #(= (:workflow-run/id run) (:attention/source-id %)))
       (sort-by :attention/derived-at)
       vec))

(defn- run-decisions [table run]
  (->> (vals (:decisions table))
       (filter #(= (:workflow-run/id run) (:decision/workflow-run-id %)))
       (sort-by :decision/created-at)
       vec))

(defn- run-interventions [table run]
  (->> (vals (:interventions table))
       (filter #(= (:workflow-run/id run) (:intervention/target-id %)))
       (sort-by :intervention/requested-at)
       vec))

;------------------------------------------------------------------------------ Layer 2
;; State-variable evaluators and public projection helpers

(defn- machine-authoritative
  "Observed phase history must be a path through the authoritative machine
   (N2 §2.1, §3.1) and a `:completed` run must end on the machine's
   terminal `:done` phase. Failed/cancelled runs are lifecycle-terminal
   from any phase per N2 §2.3."
  [{:keys [run phase-history transitions evaluated-at]}]
  (let [observed        (if (seq phase-history)
                          (vec phase-history)
                          (vec (remove #{:unknown}
                                       [(:workflow-run/current-phase run)])))
        pairs           (vec (partition 2 1 observed))
        illegal         (vec (remove (fn [[from to]]
                                       (contains? (get transitions from #{}) to))
                                     pairs))
        completed?      (= :completed (:workflow-run/status run))
        terminal-legal? (or (not completed?)
                            (= :done (last observed)))
        ok?             (and (empty? illegal) terminal-legal?)
        status          (status-for-boolean ok?)]
    (evaluation
     {:state-var-id "miniforge.workflow.machine_authoritative"
      :status status
      :value ok?
      :score (if ok? 1.0 0.0)
      :confidence 1.0
      :components {:legal_transition_rate (ratio (- (count pairs) (count illegal))
                                                 (count pairs))
                   :terminal_phase_legal (if terminal-legal? 1.0 0.0)
                   :observed_transitions (double (count pairs))}
      :evidence (into (run-evidence run evaluated-at)
                      (phase-history-evidence run observed pairs evaluated-at))
      :findings (cond-> []
                  (seq illegal)         (conj (finding "error" illegal-transition-message))
                  (not terminal-legal?) (conj (finding "error" terminal-phase-message)))
      :gate-effect (gate-effect status "blocks_transition")
      :evaluated-at evaluated-at})))

(defn- runs-resolved
  "Resolved-run boundary health across the whole table: the fraction of
   workflow runs whose lifecycle status is terminal (N2 §2.2-§2.3)."
  [{:keys [table evaluated-at]}]
  (let [runs     (vec (vals (:workflows table)))
        resolved (count (filter config/resolved? runs))
        dangling (- (count runs) resolved)
        score    (ratio resolved (count runs))
        status   (status-for-rate score)]
    (evaluation
     {:state-var-id "miniforge.workflow.runs_resolved"
      :status status
      :value score
      :score score
      :confidence 1.0
      :components {:resolved_runs (double resolved)
                   :total_runs (double (count runs))
                   :dangling_runs (double dangling)}
      :evidence (table-run-evidence runs evaluated-at)
      :findings (rate-findings status dangling-runs-message)
      :gate-effect (gate-effect status "marks_low_confidence")
      :evaluated-at evaluated-at})))

(defn- critical-violations-block
  "Every run-scoped PolicyEvaluation carrying a critical violation must have
   a blocking failed outcome (N4 §3, §3.3)."
  [{:keys [run table evaluated-at]}]
  (let [evals     (run-policy-evals table run)
        criticals (filter critical-policy-eval? evals)
        unblocked (filter :policy-eval/passed? criticals)
        ok?       (empty? unblocked)
        status    (status-for-boolean ok?)]
    (evaluation
     {:state-var-id "miniforge.gate.critical_violations_block"
      :status status
      :value ok?
      :score (if ok? 1.0 0.0)
      :confidence 1.0
      :components {:critical_evaluations (double (count criticals))
                   :blocking_evaluations (double (- (count criticals)
                                                    (count unblocked)))
                   :unblocked_evaluations (double (count unblocked))}
      :evidence (or-absence (policy-eval-evidence evals evaluated-at)
                            {:scope-id (:workflow-run/id run)
                             :source-role "policy-evaluation"
                             :quote "no policy evaluations recorded for this run"
                             :evaluated-at evaluated-at})
      :findings (when-not ok?
                  [(finding "error" unblocked-critical-message)])
      :gate-effect (gate-effect status "blocks_transition")
      :evaluated-at evaluated-at})))

(defn- policy-evaluations-recorded
  "A run that traversed `:verify` must have at least one recorded
   PolicyEvaluation (N4 §3). Runs that never reached verify are out of the
   variable's scope and read `not_applicable`."
  [{:keys [run table phase-history evaluated-at]}]
  (let [verify?    (boolean (some #{:verify} phase-history))
        evals      (run-policy-evals table run)
        components {:policy_evaluations (double (count evals))
                    :verify_traversed (if verify? 1.0 0.0)}]
    (if-not verify?
      (evaluation
       {:state-var-id "miniforge.policy.evaluations_recorded"
        :status "not_applicable"
        :score 1.0
        :confidence 1.0
        :components components
        :evidence (absence-evidence
                   {:scope-id (:workflow-run/id run)
                    :source-role "policy-evaluation"
                    :quote "run never traversed :verify; policy evaluation out of scope"
                    :evaluated-at evaluated-at})
        :gate-effect "none"
        :evaluated-at evaluated-at})
      (let [ok?    (pos? (count evals))
            status (status-for-boolean ok?)]
        (evaluation
         {:state-var-id "miniforge.policy.evaluations_recorded"
          :status status
          :value ok?
          :score (if ok? 1.0 0.0)
          :confidence 1.0
          :components components
          :evidence (or-absence (policy-eval-evidence evals evaluated-at)
                                {:scope-id (:workflow-run/id run)
                                 :source-role "policy-evaluation"
                                 :quote "run traversed :verify with no policy evaluation recorded"
                                 :evaluated-at evaluated-at})
          :findings (when-not ok?
                      [(finding "error" missing-evaluation-message)])
          :gate-effect (gate-effect status "marks_low_confidence")
          :evaluated-at evaluated-at})))))

(defn- attention-items-actioned
  "Fraction of the run's derived AttentionItems that are resolved
   (N5-delta-1 §3.1, §5). No items at all reads `not_applicable`."
  [{:keys [run table evaluated-at]}]
  (let [items    (run-attention-items table run)
        actioned (count (filter :attention/resolved? items))]
    (if (empty? items)
      (evaluation
       {:state-var-id "miniforge.attention.items_actioned"
        :status "not_applicable"
        :score 1.0
        :confidence 1.0
        :components {:actioned_items 0.0 :total_items 0.0 :open_items 0.0}
        :evidence (absence-evidence
                   {:scope-id (:workflow-run/id run)
                    :source-role "attention-item"
                    :quote "no attention items derived for this run"
                    :evaluated-at evaluated-at})
        :gate-effect "none"
        :evaluated-at evaluated-at})
      (let [score  (ratio actioned (count items))
            status (status-for-rate score)]
        (evaluation
         {:state-var-id "miniforge.attention.items_actioned"
          :status status
          :value score
          :score score
          :confidence 1.0
          :components {:actioned_items (double actioned)
                       :total_items (double (count items))
                       :open_items (double (- (count items) actioned))}
          :evidence (attention-evidence items evaluated-at)
          :findings (rate-findings status open-attention-message)
          :gate-effect (gate-effect status "marks_low_confidence")
          :evaluated-at evaluated-at})))))

(defn- decision-queue-drained
  "Fraction of the run's DecisionCards resolved and InterventionRequests
   settled (N5-delta-3 §2.4; N5-delta §3.3). An empty queue reads
   `not_applicable`."
  [{:keys [run table evaluated-at]}]
  (let [decisions     (run-decisions table run)
        interventions (run-interventions table run)
        resolved      (count (filter #(= :resolved (:decision/status %)) decisions))
        settled       (count (filter (comp settled-intervention-states
                                           :intervention/state)
                                     interventions))
        total         (+ (count decisions) (count interventions))]
    (if (zero? total)
      (evaluation
       {:state-var-id "miniforge.decision.queue_drained"
        :status "not_applicable"
        :score 1.0
        :confidence 1.0
        :components {:resolved_decisions 0.0 :total_decisions 0.0
                     :settled_interventions 0.0 :total_interventions 0.0}
        :evidence (into (absence-evidence
                         {:scope-id (:workflow-run/id run)
                          :source-role "decision-card"
                          :quote "no decision cards raised for this run"
                          :evaluated-at evaluated-at})
                        (absence-evidence
                         {:scope-id (:workflow-run/id run)
                          :source-role "intervention-request"
                          :quote "no intervention requests raised for this run"
                          :evaluated-at evaluated-at}))
        :gate-effect "none"
        :evaluated-at evaluated-at})
      (let [score  (ratio (+ resolved settled) total)
            status (status-for-rate score)]
        (evaluation
         {:state-var-id "miniforge.decision.queue_drained"
          :status status
          :value score
          :score score
          :confidence 1.0
          :components {:resolved_decisions (double resolved)
                       :total_decisions (double (count decisions))
                       :settled_interventions (double settled)
                       :total_interventions (double (count interventions))}
          :evidence (into (or-absence (decision-evidence decisions evaluated-at)
                                      {:scope-id (:workflow-run/id run)
                                       :source-role "decision-card"
                                       :quote "no decision cards raised for this run"
                                       :evaluated-at evaluated-at})
                          (or-absence (intervention-evidence interventions evaluated-at)
                                      {:scope-id (:workflow-run/id run)
                                       :source-role "intervention-request"
                                       :quote "no intervention requests raised for this run"
                                       :evaluated-at evaluated-at}))
          :findings (rate-findings status pending-queue-message)
          :gate-effect (gate-effect status "marks_low_confidence")
          :evaluated-at evaluated-at})))))

(defn provenance
  "Return stable evaluator and policy provenance for snapshot metadata."
  []
  {:policy_hash policy-hash
   :policy_version policy-version
   :evaluator_hash evaluator-hash
   :evaluator_version evaluator-version})

(defn evaluations
  "Evaluate the six shipped orchestration state variables for one resolved
   run. `ctx` carries `:run`, `:table`, `:transitions`, `:phase-history`,
   and `:evaluated-at`."
  [ctx]
  [(machine-authoritative ctx)
   (runs-resolved ctx)
   (critical-violations-block ctx)
   (policy-evaluations-recorded ctx)
   (attention-items-actioned ctx)
   (decision-queue-drained ctx)])
