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
   [ai.miniforge.workbench-etl-adapter.messages :as msg]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private registry-resource
  "Classpath location of the adapter's single evaluation-policy authority."
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
    (schema/failure :snapshot (msg/t :failure/missing-run))

    (or (nil? experiment-id) (nil? label))
    (schema/failure :snapshot (msg/t :failure/missing-variant))

    (and (some? baseline-factor-values) (not (map? baseline-factor-values)))
    (schema/failure :snapshot (msg/t :failure/invalid-baseline))

    :else
    (let [registry  (state-var-registry)
          inventory (config/factor-inventory resolved-run-config)
          changes   (when (some? baseline-factor-values)
                      (config/factor-diff baseline-factor-values (:values inventory)))]
      (cond
        (not (schema/valid? workbench-schema/StateVarRegistry registry))
        (schema/failure :snapshot (msg/t :failure/invalid-registry))

        (and (some? baseline-factor-values) (not= 1 (count changes)))
        (schema/failure :snapshot (msg/t :failure/invalid-change)
                        {:change-count (count changes)
                         :changes changes})

        :else
        (let [snapshot (core/snapshot registry result resolved-run-config inventory
                                      (first changes) opts)]
          (if (valid-snapshot? snapshot)
            (schema/success :snapshot snapshot)
            (schema/failure :snapshot (msg/t :failure/invalid-snapshot)
                            {:errors (schema/explain workbench-schema/WorkbenchSnapshot
                                                     snapshot)})))))))
