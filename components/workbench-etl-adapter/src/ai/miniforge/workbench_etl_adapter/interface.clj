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

(ns ai.miniforge.workbench-etl-adapter.interface
  "Validated public boundary for the Miniforge ETL workbench adapter."
  (:require
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.workbench-contract.schema :as workbench-schema]
   [ai.miniforge.workbench-etl-adapter.config :as config]
   [ai.miniforge.workbench-etl-adapter.core :as core]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private missing-run-message
  "ETL workbench projection requires a pipeline-run result")

(def ^:private missing-variant-message
  "ETL workbench projection requires experiment-id and label")

(def ^:private invalid-change-message
  "Candidate resolved-run configuration must differ from baseline at exactly one non-secret factor")

(def ^:private invalid-snapshot-message
  "ETL adapter emitted an invalid workbench_snapshot/v1")

(def ^:private registry-resource
  "workbench/miniforge-etl-state-vars.edn")

(defn factor-inventory
  "Return the safe, deterministic factor inventory for a resolved ETL run."
  [resolved-run-config]
  (config/factor-inventory resolved-run-config))

(defn factor-diff
  "Return changed factors between two persisted inventory value maps."
  [baseline-values candidate-values]
  (config/factor-diff baseline-values candidate-values))

(defn valid-snapshot?
  "True when `snapshot` conforms to the canonical workbench wire schema."
  [snapshot]
  (schema/valid? workbench-schema/WorkbenchSnapshot snapshot))

(defn state-var-registry
  "Load the product-owned ETL state-variable registry from the classpath."
  []
  (some-> registry-resource io/resource slurp edn/read-string))

(defn valid-registry?
  "True when the shipped ETL registry conforms to the canonical registry schema."
  []
  (schema/valid? workbench-schema/StateVarRegistry (state-var-registry)))

(defn project
  "Project an ETL result into a validated workbench snapshot.

   `opts` requires `:experiment-id` and `:label`. When
   `:baseline-factor-values` is present, exactly one non-secret factor must
   differ or projection fails. Returns a standard schema success/failure map."
  [result resolved-run-config
   {:keys [experiment-id label baseline-factor-values] :as opts}]
  (cond
    (not (map? (:pipeline-run result)))
    (schema/failure :snapshot missing-run-message)

    (or (nil? experiment-id) (nil? label))
    (schema/failure :snapshot missing-variant-message)

    :else
    (let [inventory (config/factor-inventory resolved-run-config)
          changes   (when baseline-factor-values
                      (config/factor-diff baseline-factor-values (:values inventory)))]
      (if (and baseline-factor-values (not= 1 (count changes)))
        (schema/failure :snapshot invalid-change-message
                        {:change-count (count changes)
                         :changes changes})
        (let [snapshot (core/snapshot result resolved-run-config inventory
                                      (first changes) opts)]
          (if (valid-snapshot? snapshot)
            (schema/success :snapshot snapshot)
            (schema/failure :snapshot invalid-snapshot-message
                            {:errors (schema/explain workbench-schema/WorkbenchSnapshot
                                                     snapshot)})))))))
