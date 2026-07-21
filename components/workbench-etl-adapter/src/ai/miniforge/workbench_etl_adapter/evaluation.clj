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

(ns ai.miniforge.workbench-etl-adapter.evaluation
  "Pure ETL state-variable evaluation and evaluator provenance."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash]
   [ai.miniforge.workbench-etl-adapter.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0
;; Evaluation policy

(def ^:private product
  "Product identity stamped on every Miniforge-owned ETL evaluation."
  "miniforge")

(def ^:private run-completed-id
  "The registry key for terminal ETL-run evaluation."
  "miniforge.etl.run_completed")

(def ^:private stages-completed-id
  "The registry key for scheduled-stage completion evaluation."
  "miniforge.etl.stages_completed")

(def ^:private data-quality-pass-rate-id
  "The registry key for record-level data-quality evaluation."
  "miniforge.etl.data_quality_pass_rate")

(def ^:private evaluator-version
  "Version of the code formulas that turn ETL results into evaluations."
  "miniforge-etl-workbench-adapter/1.0.0")

(def ^:private evaluator-definition
  "Stable formula identity hashed independently from operator policy."
  {:evaluators
   [{:id run-completed-id :formula :completed-status}
    {:id stages-completed-id :formula :completed-stages-over-all-stages}
    {:id data-quality-pass-rate-id :formula :passed-records-over-evaluated-records}]})

(def ^:private evaluator-hash
  "Digest of the formula identities implemented by this evaluator version."
  (str "sha256:" (content-hash/content-hash evaluator-definition)))

(defn- state-var [registry state-var-id]
  (some #(when (= state-var-id (:id %)) %) (:state_vars registry)))

(defn- status-for-score [score thresholds]
  (cond
    (>= score (:pass thresholds)) :pass
    (and (:warn thresholds) (>= score (:warn thresholds))) :warn
    :else :fail))

(defn- gate-effect [state-var status]
  (get-in state-var [:gate_effects status] (name :none)))

(defn- required-source-role [state-var]
  (first (get-in state-var [:evidence_requirements :required_refs])))

(defn- ratio [numerator denominator]
  (if (zero? denominator)
    1.0
    (double (/ numerator denominator))))

;------------------------------------------------------------------------------ Layer 1
;; Evidence, evaluation assembly, and state-variable evaluators

(defn- evaluation
  [{:keys [state-var-id status value score confidence components evidence findings
           gate-effect evaluated-at]}]
  (cond-> {:state_var_id state-var-id
           :product product
           :status (name status)
           :score score
           :confidence confidence
           :evidence_refs (vec evidence)
           :findings (vec findings)
           :gate_effect gate-effect
           :evaluated_at evaluated-at}
    (some? value)      (assoc :value value)
    (some? components) (assoc :score_components components)))

(defn- run-evidence [run evaluated-at source-role]
  [{:id (str run-completed-id ".pipeline-run." (:pipeline-run/id run))
    :source_role source-role
    :quote (msg/t :evidence/run-status
                  {:status (name (:pipeline-run/status run))})
    :created_at evaluated-at}])

(defn- stage-evidence [stage-runs run-id evaluated-at source-role]
  (if (seq stage-runs)
    (mapv (fn [index stage]
            {:id (str stages-completed-id ".stage." index)
             :source_role source-role
             :quote (msg/t :evidence/stage-status
                           {:stage (:stage/name stage)
                            :status (name (:status stage))})
             :created_at evaluated-at})
          (range)
          stage-runs)
    [{:id (str stages-completed-id ".absent." run-id)
      :source_role source-role
      :quote (msg/t :evidence/no-stage-runs)
      :created_at evaluated-at}]))

(defn- quality-evidence [quality-reports evaluated-at source-role]
  (mapv (fn [index report]
          {:id (str data-quality-pass-rate-id ".quality-report." index)
           :source_role source-role
           :quote (msg/t :evidence/quality-summary
                         {:passed (:report/passed report)
                          :total (:report/total report)})
           :created_at evaluated-at})
        (range)
        quality-reports))

(defn- run-completed [registry run evaluated-at]
  (let [definition (state-var registry run-completed-id)
        completed? (= :completed (:pipeline-run/status run))
        score      (if completed? 1.0 0.0)
        status     (status-for-score score (:thresholds definition))]
    (evaluation
     {:state-var-id run-completed-id
      :status status
      :value completed?
      :score score
      :confidence 1.0
      :components {:completed score}
      :evidence (run-evidence run evaluated-at (required-source-role definition))
      :findings (when-not completed?
                  [{:severity (name :error)
                    :message (msg/t :finding/run-incomplete)}])
      :gate-effect (gate-effect definition status)
      :evaluated-at evaluated-at})))

(defn- stages-completed [registry run stage-runs configured-stage-count evaluated-at]
  (let [;; Failed execution stops before skipped stages enter :stage-runs.
        definition (state-var registry stages-completed-id)
        total      (max configured-stage-count (count stage-runs))
        completed  (count (filter #(= :completed (:status %)) stage-runs))
        failed     (count (filter #(= :failed (:status %)) stage-runs))
        score      (ratio completed total)
        status     (status-for-score score (:thresholds definition))]
    (evaluation
     {:state-var-id stages-completed-id
      :status status
      :value score
      :score score
      :confidence 1.0
      :components {:completed_stages (double completed)
                   :total_stages (double total)
                   :failed_stages (double failed)}
      :evidence (stage-evidence stage-runs (:pipeline-run/id run) evaluated-at
                                (required-source-role definition))
      :findings (when (not= :pass status)
                  [{:severity (name (if (= :fail status) :error :warn))
                    :message (msg/t :finding/stage-incomplete)}])
      :gate-effect (gate-effect definition status)
      :evaluated-at evaluated-at})))

(defn- data-quality [registry stage-runs evaluated-at]
  (let [definition (state-var registry data-quality-pass-rate-id)
        reports    (->> stage-runs (keep :quality-report) vec)]
    (if (empty? reports)
      (evaluation
       {:state-var-id data-quality-pass-rate-id
        :status :not_applicable
        :score 1.0
        :confidence 1.0
        :components {:passed_records 0.0
                     :evaluated_records 0.0
                     :failed_records 0.0}
        :gate-effect (name :none)
        :evaluated-at evaluated-at})
      (let [total  (reduce + (map :report/total reports))
            passed (reduce + (map :report/passed reports))
            failed (reduce + (map :report/failed reports))
            score  (ratio passed total)
            status (status-for-score score (:thresholds definition))]
        (evaluation
         {:state-var-id data-quality-pass-rate-id
          :status status
          :value score
          :score score
          :confidence 1.0
          :components {:passed_records (double passed)
                       :evaluated_records (double total)
                       :failed_records (double failed)}
          :evidence (quality-evidence reports evaluated-at
                                      (required-source-role definition))
          :findings (when (not= :pass status)
                      [{:severity (name (if (= :fail status) :error :warn))
                        :message (msg/t :finding/quality-failed)}])
          :gate-effect (gate-effect definition status)
          :evaluated-at evaluated-at})))))

;------------------------------------------------------------------------------ Layer 2
;; Public projection helpers

(defn provenance
  "Return stable evaluator and policy provenance for snapshot metadata."
  [registry]
  {:policy_hash (str "sha256:" (content-hash/content-hash registry))
   :policy_version (:version registry)
   :evaluator_hash evaluator-hash
   :evaluator_version evaluator-version})

(defn evaluations
  "Evaluate the three shipped ETL state variables for one pipeline run."
  [registry run stage-runs configured-stage-count evaluated-at]
  [(run-completed registry run evaluated-at)
   (stages-completed registry run stage-runs configured-stage-count evaluated-at)
   (data-quality registry stage-runs evaluated-at)])
