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
(ns ai.miniforge.redaction.policy
  "The redaction policy: which values N3 §8.1 excludes, and the marker
   §8.2 substitutes.

   Patterns live in EDN and are compiled here — config-as-data (dewey
   007), and N8 §5.2 forbids a function as a redaction configuration
   value since it cannot be serialized, diffed, or audited."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private config-resource
  "config/redaction/patterns.edn")

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} policy
  "The redaction policy, patterns compiled.

   EDN has no regex literal, so the config holds pattern strings and
   they are compiled here — the policy stays inspectable data on disk
   (dewey 007) and becomes usable regexes exactly once."
  (delay
    (let [raw (-> config-resource io/resource slurp edn/read-string)]
      (-> raw
          (update :redaction/secret-key-patterns #(mapv re-pattern %))
          (update :redaction/secret-value-patterns #(mapv re-pattern %))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} marker
  "The substitution marker. A present marker records that something
   existed and was withheld; an omitted key would be
   indistinguishable from a field that was never populated (N3 §8.2)."
  []
  (:redaction/marker @policy))
