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

(ns ai.miniforge.etl.workbench
  "File boundary between `mf etl run` and the pure ETL workbench adapter.
   Reads the optional prior snapshot used by the current file-based baseline
   workflow; all factor inventory, diffing, evaluation, and contract validation
   remain in the product adapter component."
  {:miniforge/runtime :jvm-only}
  (:require
   [ai.miniforge.etl.messages :as msg]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.workbench-etl-adapter.interface :as adapter]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def ^:private sha256-pattern #"^sha256:[0-9a-f]{64}$")

(defn missing-option
  "Return the first required workbench option absent from `opts`, or nil."
  [{:keys [experiment-id label source-hash]}]
  (cond
    (str/blank? experiment-id) "experiment-id"
    (str/blank? label)         "label"
    (str/blank? source-hash)   "source-hash"
    :else nil))

(defn valid-source-hash?
  "True for a lowercase, prefixed SHA-256 workload digest."
  [source-hash]
  (boolean (and source-hash (re-matches sha256-pattern source-hash))))

(defn state-var-registry
  "Return the product-owned ETL state-variable registry."
  []
  (adapter/state-var-registry))

(defn valid-registry?
  "True when the shipped registry conforms to the workbench contract."
  []
  (adapter/valid-registry?))

(defn- read-snapshot [path]
  (let [raw (slurp path)]
    (if (str/ends-with? path ".json")
      ;; Keep dynamic factor-map keys as strings. Keywordizing every JSON key
      ;; would reinterpret factor ids containing namespaced EDN keywords (for
      ;; example `:pipeline/mode`) as Clojure keyword namespaces.
      (json/parse-string raw)
      (edn/read-string raw))))

(defn- baseline-context [path]
  (when path
    (let [snapshot (read-snapshot path)
          json?    (str/ends-with? path ".json")]
      {:factor-values (get-in snapshot (if json?
                                        ["metadata" "resolved_run" "factors"]
                                        [:metadata :resolved_run :factors]))
       :source-hashes (get snapshot (if json? "source_hashes" :source_hashes))})))

(defn project
  "Project `result` using the current resolved-run config and optional prior
   snapshot path. Returns schema success/failure; catches file/parse exceptions
   at this absolute CLI boundary."
  [result run-configuration
   {:keys [baseline source-hash] :as opts}]
  (try
    (let [{:keys [factor-values source-hashes]} (baseline-context baseline)
          current-source-hashes [source-hash]]
      (cond
        (and baseline (nil? factor-values))
        (schema/failure :snapshot (msg/t :workbench/baseline-missing-factors))

        (and baseline (not= source-hashes current-source-hashes))
        (schema/failure :snapshot (msg/t :workbench/source-hash-mismatch))

        :else
        (adapter/project result run-configuration
                         (cond-> (assoc opts :source-hashes current-source-hashes)
                           baseline (assoc :baseline-factor-values factor-values)))))
    (catch Exception e
      (schema/exception-failure :snapshot e))))
