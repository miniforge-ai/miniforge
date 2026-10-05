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
(ns ai.miniforge.workflow.chain-loader
  "Chain definition loading from classpath resources.
   Loads chain EDN files from resources/chains/ directory.
   Works both on filesystem (dev) and inside uberjars."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.workflow.chain-resources :as resources]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} find-latest-chain-resource
  "Find the highest-versioned chain EDN resource path for chain-id.
   Returns a resource path string like \"chains/reporting-chain-v1.0.0.edn\"."
  [chain-id]
  (let [prefix (str (name chain-id) "-v")
        candidates (->> (resources/names "chains")
                        (filter #(str/ends-with? % ".edn"))
                        (filter #(str/starts-with? % prefix))
                        sort
                        reverse)]
    (when-let [filename (first candidates)]
      (str "chains/" filename))))

(defn ^{:stratum 0} list-chains
  "List all available chain definitions from classpath."
  []
  (some->> (resources/names "chains")
           (filter #(str/ends-with? % ".edn"))
           (map #(str "chains/" %))
           (keep resources/summary)
           vec))

;------------------------------------------------------------------------------ Layer 1

;; Public API
(defn ^{:stratum 1} try-load-chain
  "Anomaly-returning chain loader.

   Arguments:
   - chain-id: Chain identifier (keyword, e.g. :reporting-chain)
   - version: Version string (e.g. \"1.0.0\" or \"latest\")

   Returns:
   - on success: `{:chain chain-def :source :resource :path resource-path}`
   - on miss:    a `:not-found` anomaly carrying `:chain-id`, `:version`,
                 `:looked-for` (vector of paths attempted)

   This is the canonical, anomaly-returning entry point. The boundary
   site `load-chain` inlines a `response/throw-anomaly!` when an
   anomaly is observed, preserving the legacy thrown-exception
   contract for external callers that depend on it."
  [chain-id version]
  (let [versioned-path (str "chains/" (name chain-id) "-v" version ".edn")
        base-path (str "chains/" (name chain-id) ".edn")
        resource-path (or (when (and version (not= version "latest"))
                            (when (io/resource versioned-path)
                              versioned-path))
                          (when (io/resource base-path) base-path)
                          (find-latest-chain-resource chain-id))
        chain-def (when resource-path (resources/read-definition resource-path))]
    (if chain-def
      {:chain chain-def :source :resource :path resource-path}
      (anomaly/anomaly :not-found
                       (str "Chain '" (name chain-id) "' not found. "
                            "Looked for: " versioned-path ", " base-path)
                       {:chain-id chain-id
                        :version version
                        :looked-for [versioned-path base-path]}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-chain
  "Load a chain definition from classpath resources.

   Arguments:
   - chain-id: Chain identifier (keyword, e.g. :reporting-chain)
   - version: Version string (e.g. \"1.0.0\" or \"latest\")

   Returns the chain definition result map on success.
   Throws via `response/throw-anomaly!` with category
   `:anomalies/not-found` when no matching chain resource exists.

   For an anomaly-returning equivalent that callers can branch on as
   data, use `try-load-chain` directly."
  [chain-id version]
  (let [result (try-load-chain chain-id version)]
    (if (anomaly/anomaly? result)
      (response/throw-anomaly! :anomalies/not-found
                               (:anomaly/message result)
                               (:anomaly/data result))
      result)))

(comment
  (list-chains))
