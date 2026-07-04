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

(ns ai.miniforge.workbench-sweep.interface
  "Public API of the workbench sweep runner: execute a declared
   permutation matrix (variants x replicates) and emit one variant-tagged
   `workbench_snapshot/v1` per run, ready for `minibench compare`.

   The config is validated up front; execution is a command template per
   run (see `ai.miniforge.workbench-sweep.schema/SweepConfig`), so the
   runner is agnostic to HOW a run executes — `mf workflow execute`,
   `mf chain run`, a pipeline script — as long as it leaves a supervisory
   PolicyEvaluation EDN at the templated `{out}` path."
  (:require [ai.miniforge.workbench-adapter.interface :as adapter]
            [ai.miniforge.workbench-sweep.core :as core]
            [ai.miniforge.workbench-sweep.schema :as schema]
            [clojure.edn :as edn]
            [malli.core :as m]
            [malli.error :as me]))

(defn- validate-config! [config]
  (when-not (m/validate schema/SweepConfig config)
    (throw (ex-info "Invalid sweep config"
                    {:kind :sweep-config
                     :errors (me/humanize (m/explain schema/SweepConfig config))})))
  (when-let [missing (seq (filter #(and (nil? (:policy-eval %))
                                        (nil? (:command config)))
                                  (:variants config)))]
    (throw (ex-info "Variants without :policy-eval need a :command template"
                    {:kind :sweep-config
                     :labels (mapv :label missing)})))
  config)

(defn run-sweep!
  "Validate and execute a sweep config map. Returns
   `{:snapshots [paths] :out-dir ...}`."
  [config]
  (validate-config! config)
  (core/run-sweep! config nil))

(defn sweep!
  "`clojure -X` entry: run a sweep from an EDN config file.
   Args: `{:config \"/path/to/sweep.edn\"}`. The config file's sha256
   rides in every emitted snapshot's metadata, so a matrix of results is
   traceable to the exact matrix definition that produced it."
  [{:keys [config]}]
  (let [path (str config)
        cfg (validate-config! (edn/read-string (slurp path)))
        config-sha (adapter/sha256-file path)
        {:keys [snapshots out-dir]} (core/run-sweep! cfg config-sha)]
    (println (format "sweep complete: %d snapshot(s) in %s" (count snapshots) out-dir))
    (println "next:    minibench compare" out-dir))
  nil)
