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

(ns ai.miniforge.workbench-orchestration-adapter.interface
  "Validated public boundary for the Miniforge orchestration workbench
   adapter: projects supervisory-state (EntityTable) into a validated
   `workbench_snapshot/v1` at the resolved-run boundary."
  (:require
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.workbench-contract.schema :as workbench-schema]
   [ai.miniforge.workbench-orchestration-adapter.config :as config]
   [ai.miniforge.workbench-orchestration-adapter.core :as core]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private missing-run-message
  "Orchestration workbench projection requires a known workflow run")

(def ^:private unresolved-run-message
  "Orchestration workbench projection only admits resolved (terminal) workflow runs")

(def ^:private missing-variant-message
  "Orchestration workbench projection requires experiment-id and label")

(def ^:private missing-transitions-message
  "Orchestration workbench projection requires the legal phase-transition map")

(def ^:private invalid-snapshot-message
  "Orchestration adapter emitted an invalid workbench_snapshot/v1")

(def ^:private missing-out-message
  "Orchestration registry export requires an :out path")

(def ^:private registry-resource
  "workbench/miniforge-orchestration-state-vars.edn")

(defn resolved?
  "True when `run` (a supervisory-state WorkflowRun entity) has reached the
   resolved-run boundary - a terminal lifecycle status per N2 §2.2-§2.3."
  [run]
  (config/resolved? run))

(defn resolved-runs
  "All resolved WorkflowRun entities in `table`, ordered by started-at.
   Unresolved (dangling) runs are excluded from snapshots by default."
  [table]
  (config/resolved-runs table))

(defn phase-history
  "Ordered `:workflow/phase-started` phases observed for `workflow-id` in
   `events` - the same event stream the caller folded into the table."
  [events workflow-id]
  (config/phase-history events workflow-id))

(defn valid-snapshot?
  "True when `snapshot` conforms to the canonical workbench wire schema."
  [snapshot]
  (schema/valid? workbench-schema/WorkbenchSnapshot snapshot))

(defn state-var-registry
  "Load the product-owned orchestration state-variable registry from the
   classpath."
  []
  (some-> registry-resource io/resource slurp edn/read-string))

(defn valid-registry?
  "True when the shipped orchestration registry conforms to the canonical
   registry schema."
  []
  (schema/valid? workbench-schema/StateVarRegistry (state-var-registry)))

(defn project
  "Project one resolved workflow run from `table` into a validated
   workbench snapshot.

   `opts` requires `:experiment-id`, `:label`, and `:transitions` (the
   legal phase-transition map from the workflow component's interface).
   `:phase-history` carries the run's observed phases (see
   [[phase-history]]); `:generated-at`, `:snapshot-id`, `:run-id`, and
   `:source-hashes` are optional. Unresolved runs are rejected at the
   boundary. Returns a standard schema success/failure map."
  [table workflow-run-id {:keys [experiment-id label transitions] :as opts}]
  (let [run (get-in table [:workflows workflow-run-id])]
    (cond
      (nil? run)
      (schema/failure :snapshot missing-run-message
                      {:workflow-run-id workflow-run-id})

      (not (config/resolved? run))
      (schema/failure :snapshot unresolved-run-message
                      {:workflow-run-id workflow-run-id
                       :status (:workflow-run/status run)})

      (or (nil? experiment-id) (nil? label))
      (schema/failure :snapshot missing-variant-message)

      (not (map? transitions))
      (schema/failure :snapshot missing-transitions-message)

      :else
      (let [snapshot (core/snapshot table run opts)]
        (if (valid-snapshot? snapshot)
          (schema/success :snapshot snapshot)
          (schema/failure :snapshot invalid-snapshot-message
                          {:errors (schema/explain workbench-schema/WorkbenchSnapshot
                                                   snapshot)}))))))

(defn export-registry!
  "Write the shipped registry as pretty-printed JSON to `:out`. Companion
   to the `workbench:orchestration:registry` bb task; the JSON shape
   matches the minibench workbench-contract fixture registry."
  [{:keys [out]}]
  (when (or (nil? out) (and (string? out) (str/blank? out)))
    (throw (ex-info missing-out-message {:out out})))
  (spit (str out) (json/generate-string (state-var-registry) {:pretty true}))
  (println "Wrote" (str out)))
