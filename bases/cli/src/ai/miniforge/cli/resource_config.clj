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

(ns ai.miniforge.cli.resource-config
  "Helpers for resource-backed CLI configuration and messages."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Resource loading

(defn resource-precedence
  "Sort classpath resources so base defaults load before component/project overrides."
  [resource]
  (let [path (str resource)]
    (cond
      (str/includes? path "/bases/") 0
      (str/includes? path "/components/") 1
      (str/includes? path "/projects/") 2
      :else 3)))

(defn read-edn-resource
  [resource]
  (-> resource slurp edn/read-string))

(defn merged-resource-config
  "Load and merge EDN resources from the classpath.

   `section-key` extracts the relevant sub-map from each resource when provided."
  ([resource-path]
   (merged-resource-config resource-path nil {}))
  ([resource-path section-key]
   (merged-resource-config resource-path section-key {}))
  ([resource-path section-key defaults]
   (->> (enumeration-seq (.getResources (clojure.lang.RT/baseLoader) resource-path))
        (sort-by resource-precedence)
        (map read-edn-resource)
        (map #(if section-key
                (or (get % section-key) {})
                %))
        (reduce merge defaults))))
