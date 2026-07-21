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

(def ^:private policy-version
  "Version of the initial in-code ETL evaluation policy."
  "miniforge-etl-evaluation-policy/1.0.0")

(def ^:private stage-warn-threshold
  "Minimum scheduled-stage completion rate that avoids a failing status."
  0.8)

(def ^:private quality-pass-threshold
  "Record acceptance rate required for a passing quality status."
  0.98)

(def ^:private quality-warn-threshold
  "Minimum record acceptance rate that avoids a failing quality status."
  0.9)

(def ^:private evaluator-definition
  "Stable formula identity hashed independently from operator policy."
  {:evaluators
   [{:id run-completed-id :formula :completed-status}
    {:id stages-completed-id :formula :completed-stages-over-all-stages}
    {:id data-quality-pass-rate-id :formula :passed-records-over-evaluated-records}]})

(def ^:private evaluator-hash
  "Digest of the formula identities implemented by this evaluator version."
  (str "sha256:" (content-hash/content-hash evaluator-definition)))

(def ^:private policy-definition
  "Threshold inputs hashed to identify the in-code evaluation policy."
  {:stage-warn-threshold stage-warn-threshold
   :quality-pass-threshold quality-pass-threshold
   :quality-warn-threshold quality-warn-threshold})

(def ^:private policy-hash
  "Digest used to distinguish changes to the in-code policy thresholds."
  (str "sha256:" (content-hash/content-hash policy-definition)))

(defn- status-for-stage-rate [score]
  (cond
    (= 1.0 score) :pass
    (>= score stage-warn-threshold) :warn
    :else :fail))

(defn- status-for-quality-rate [score]
  (cond
    (>= score quality-pass-threshold) :pass
    (>= score quality-warn-threshold) :warn
    :else :fail))

(defn- gate-effect [status failure-effect]
  (cond
    (= :pass status) (name :none)
    (= :warn status) (name :marks_low_confidence)
    :else failure-effect))

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

(defn- run-evidence [run evaluated-at]
  [{:id (str run-completed-id ".pipeline-run." (:pipeline-run/id run))
    :source_role (name :pipeline-run)
    :quote (msg/t :evidence/run-status
                  {:status (:pipeline-run/status run)})
    :created_at evaluated-at}])

(defn- stage-evidence [stage-runs evaluated-at]
  (mapv (fn [index stage]
          {:id (str stages-completed-id ".stage." index)
           :source_role (name :pipeline-stage-run)
           :quote (msg/t :evidence/stage-status
                         {:stage (:stage/name stage)
                          :status (:status stage)})
           :created_at evaluated-at})
        (range)
        stage-runs))

(defn- quality-evidence [quality-reports evaluated-at]
  (mapv (fn [index report]
          {:id (str data-quality-pass-rate-id ".quality-report." index)
           :source_role (name :data-quality-report)
           :quote (msg/t :evidence/quality-summary
                         {:passed (:report/passed report)
                          :total (:report/total report)})
           :created_at evaluated-at})
        (range)
        quality-reports))

(defn- run-completed [run evaluated-at]
  (let [completed? (= :completed (:pipeline-run/status run))
        score      (if completed? 1.0 0.0)
        status     (if completed? :pass :fail)]
    (evaluation
     {:state-var-id run-completed-id
      :status status
      :value completed?
      :score score
      :confidence 1.0
      :components {:completed score}
      :evidence (run-evidence run evaluated-at)
      :findings (when-not completed?
                  [{:severity (name :error)
                    :message (msg/t :finding/run-incomplete)}])
      :gate-effect (gate-effect status (name :blocks_release))
      :evaluated-at evaluated-at})))

(defn- stages-completed [stage-runs configured-stage-count evaluated-at]
  (let [;; Failed execution stops before skipped stages enter :stage-runs.
        total     (max configured-stage-count (count stage-runs))
        completed (count (filter #(= :completed (:status %)) stage-runs))
        failed    (count (filter #(= :failed (:status %)) stage-runs))
        score     (ratio completed total)
        status    (status-for-stage-rate score)]
    (evaluation
     {:state-var-id stages-completed-id
      :status status
      :value score
      :score score
      :confidence 1.0
      :components {:completed_stages (double completed)
                   :total_stages (double total)
                   :failed_stages (double failed)}
      :evidence (stage-evidence stage-runs evaluated-at)
      :findings (when (not= :pass status)
                  [{:severity (name (if (= :fail status) :error :warn))
                    :message (msg/t :finding/stage-incomplete)}])
      :gate-effect (gate-effect status (name :blocks_release))
      :evaluated-at evaluated-at})))

(defn- data-quality [stage-runs evaluated-at]
  (let [reports (->> stage-runs (keep :quality-report) vec)]
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
            status (status-for-quality-rate score)]
        (evaluation
         {:state-var-id data-quality-pass-rate-id
          :status status
          :value score
          :score score
          :confidence 1.0
          :components {:passed_records (double passed)
                       :evaluated_records (double total)
                       :failed_records (double failed)}
          :evidence (quality-evidence reports evaluated-at)
          :findings (when (not= :pass status)
                      [{:severity (name (if (= :fail status) :error :warn))
                        :message (msg/t :finding/quality-failed)}])
          :gate-effect (gate-effect status (name :blocks_publish))
          :evaluated-at evaluated-at})))))

;------------------------------------------------------------------------------ Layer 2
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
