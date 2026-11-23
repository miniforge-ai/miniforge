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
   [clojure.string :as str]
   [ai.miniforge.workflow.chain-resources :as resources]
   [ai.miniforge.workflow.chain-selection :as selection]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} find-latest-chain-resource
  "Find the lexically last versioned chain EDN resource path for chain-id.
   Returns a resource path string like \"chains/reporting-chain-v1.0.0.edn\"."
  [chain-id]
  (let [prefix (str (name chain-id) "-v")]
    (some->> (resources/names "chains")
             (filter #(str/ends-with? % ".edn"))
             (filter #(str/starts-with? % prefix))
             sort last (str "chains/"))))

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
  "Load the requested identity/version or return an anomaly; exact misses never fall back.
   Nil, :latest and \"latest\" select a resolved definition. Selection precedes execution."
  [chain-id version]
  (let [request (selection/request chain-id version)]
    (if (selection/valid-request? request)
      (let [paths (resources/candidates request find-latest-chain-resource)
            path (some resources/existing paths)
            definition (some-> path resources/read-definition)]
        (selection/result (assoc request :looked-for paths) path definition))
      (selection/invalid-request request))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} load-chain
  "Load a chain definition from classpath resources.

   Arguments:
   - chain-id: Chain identifier (keyword, e.g. :reporting-chain)
   - version: Version string (e.g. \"1.0.0\" or \"latest\")

   Returns the chain definition result map on success.
   Throws via `response/throw-anomaly!` with category
   corresponding to the returned anomaly (not-found, invalid-input, or fault).

   For an anomaly-returning equivalent that callers can branch on as
   data, use `try-load-chain` directly."
  [chain-id version]
  (let [result (try-load-chain chain-id version)]
    (if (anomaly/anomaly? result)
      (response/throw-anomaly! (keyword "anomalies" (name (:anomaly/type result)))
                               (:anomaly/message result)
                               (:anomaly/data result))
      result)))

(comment
  (list-chains))
