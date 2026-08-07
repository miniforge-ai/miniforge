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
(ns ai.miniforge.governance-provenance.git-parse
  "Pure parsers for local git projection output."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} separator "\u001f")

(defn ^{:stratum 0} parse-blame-shas
  [output]
  (->> (str/split-lines (or output ""))
       (keep #(some->> (re-matches #"^\^?([0-9a-f]{40}) \d+ \d+(?: \d+)?$" %)
                       second))
       distinct
       vec))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} parse-fields
  [line]
  (str/split (or line "") (re-pattern separator) -1))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} parse-pull-request
  [output]
  (some (fn [line]
          (let [[merge-sha subject] (parse-fields line)]
            (when-let [[_ number] (and (re-matches #"[0-9a-f]{40}" (or merge-sha ""))
                                       (re-find #"(?i)\bmerge(?: pull request| pr)? #(\d+)\b"
                                                (or subject "")))]
              {:pull-request/number (parse-long number)
               :pull-request/merge-commit merge-sha
               :pull-request/source :local-merge-history})))
        (str/split-lines (or output ""))))
