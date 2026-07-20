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
   [ai.miniforge.content-hash.interface :as content-hash]))

;------------------------------------------------------------------------------ Layer 0
;; Evaluation policy

(def ^:private product "miniforge")
(def ^:private evaluator-version "miniforge-etl-workbench-adapter/1.0.0")
(def ^:private policy-version "miniforge-etl-evaluation-policy/1.0.0")
(def ^:private stage-warn-threshold 0.8)
(def ^:private quality-pass-threshold 0.98)
(def ^:private quality-warn-threshold 0.9)

(def ^:private evaluator-definition
  {:evaluators
   [{:id "miniforge.etl.run_completed" :formula :completed-status}
    {:id "miniforge.etl.stages_completed" :formula :completed-stages-over-all-stages}
    {:id "miniforge.etl.data_quality_pass_rate" :formula :passed-records-over-evaluated-records}]})

(def ^:private policy-definition
  {:stage-warn-threshold stage-warn-threshold
   :quality-pass-threshold quality-pass-threshold
   :quality-warn-threshold quality-warn-threshold})

(def ^:private evaluator-hash
  (str "sha256:" (content-hash/content-hash evaluator-definition)))

(def ^:private policy-hash
  (str "sha256:" (content-hash/content-hash policy-definition)))

(def ^:private run-incomplete-message "Pipeline run did not complete")
(def ^:private stage-incomplete-message "One or more pipeline stages did not complete")
(def ^:private quality-failed-message "One or more evaluated records failed data-quality rules")

(defn- ratio [numerator denominator]
  (if (zero? denominator)
    1.0
    (double (/ numerator denominator))))

(defn- status-for-stage-rate [score]
  (cond
    (= 1.0 score) "pass"
    (>= score stage-warn-threshold) "warn"
    :else "fail"))

(defn- status-for-quality-rate [score]
  (cond
    (>= score quality-pass-threshold) "pass"
    (>= score quality-warn-threshold) "warn"
    :else "fail"))

(defn- gate-effect [status failure-effect]
  (case status
    "pass" "none"
    "warn" "marks_low_confidence"
    failure-effect))

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
  [{:id (str "miniforge.etl.pipeline-run." (:pipeline-run/id run))
    :source_role "pipeline-run"
    :quote (str "pipeline status " (:pipeline-run/status run))
    :created_at evaluated-at}])

(defn- stage-evidence [stage-runs evaluated-at]
  (mapv (fn [index stage]
          {:id (str "miniforge.etl.stage." index)
           :source_role "pipeline-stage-run"
           :quote (str (:stage/name stage) " status " (:status stage))
           :created_at evaluated-at})
        (range)
        stage-runs))

(defn- quality-evidence [quality-reports evaluated-at]
  (mapv (fn [index report]
          {:id (str "miniforge.etl.quality-report." index)
           :source_role "data-quality-report"
           :quote (str (:report/passed report) "/" (:report/total report)
                       " records passed")
           :created_at evaluated-at})
        (range)
        quality-reports))

;------------------------------------------------------------------------------ Layer 2
;; State-variable evaluators

(defn- run-completed [run evaluated-at]
  (let [completed? (= :completed (:pipeline-run/status run))
        status     (if completed? "pass" "fail")
        score      (if completed? 1.0 0.0)]
    (evaluation
     {:state-var-id "miniforge.etl.run_completed"
      :status status
      :value completed?
      :score score
      :confidence 1.0
      :components {:completed score}
      :evidence (run-evidence run evaluated-at)
      :findings (when-not completed?
                  [{:severity "error" :message run-incomplete-message}])
      :gate-effect (gate-effect status "blocks_release")
      :evaluated-at evaluated-at})))

(defn- stages-completed [stage-runs configured-stage-count evaluated-at]
  (let [;; Failed execution stops before skipped stages enter :stage-runs.
        total     (max configured-stage-count (count stage-runs))
        completed (count (filter #(= :completed (:status %)) stage-runs))
        failed    (count (filter #(= :failed (:status %)) stage-runs))
        score     (ratio completed total)
        status    (status-for-stage-rate score)]
    (evaluation
     {:state-var-id "miniforge.etl.stages_completed"
      :status status
      :value score
      :score score
      :confidence 1.0
      :components {:completed_stages (double completed)
                   :total_stages (double total)
                   :failed_stages (double failed)}
      :evidence (stage-evidence stage-runs evaluated-at)
      :findings (when (not= "pass" status)
                  [{:severity (if (= "fail" status) "error" "warn")
                    :message stage-incomplete-message}])
      :gate-effect (gate-effect status "blocks_release")
      :evaluated-at evaluated-at})))

(defn- data-quality [stage-runs evaluated-at]
  (let [reports (->> stage-runs (keep :quality-report) vec)]
    (if (empty? reports)
      (evaluation
       {:state-var-id "miniforge.etl.data_quality_pass_rate"
        :status "not_applicable"
        :score 1.0
        :confidence 1.0
        :components {:reports 0.0}
        :gate-effect "none"
        :evaluated-at evaluated-at})
      (let [total  (reduce + (map :report/total reports))
            passed (reduce + (map :report/passed reports))
            failed (reduce + (map :report/failed reports))
            score  (ratio passed total)
            status (status-for-quality-rate score)]
        (evaluation
         {:state-var-id "miniforge.etl.data_quality_pass_rate"
          :status status
          :value score
          :score score
          :confidence 1.0
          :components {:passed_records (double passed)
                       :evaluated_records (double total)
                       :failed_records (double failed)}
          :evidence (quality-evidence reports evaluated-at)
          :findings (when (not= "pass" status)
                      [{:severity (if (= "fail" status) "error" "warn")
                        :message quality-failed-message}])
          :gate-effect (gate-effect status "blocks_publish")
          :evaluated-at evaluated-at})))))

;------------------------------------------------------------------------------ Layer 3
;; Public projection helpers

(defn provenance
  "Return stable evaluator and policy provenance for snapshot metadata."
  []
  {:policy_hash policy-hash
   :policy_version policy-version
   :evaluator_hash evaluator-hash
   :evaluator_version evaluator-version})

(defn evaluations
  "Evaluate the three shipped ETL state variables for one pipeline run."
  [run stage-runs configured-stage-count evaluated-at]
  [(run-completed run evaluated-at)
   (stages-completed stage-runs configured-stage-count evaluated-at)
   (data-quality stage-runs evaluated-at)])
