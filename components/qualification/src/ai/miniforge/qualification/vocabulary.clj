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
(ns ai.miniforge.qualification.vocabulary
  "The closed vocabularies and scalar shapes qualification values use, and
   the rule that turns criterion statuses into a verdict."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} report-version
  "The report format revision every report names."
  "qualification-report/v1")

(def ^{:stratum 0} statuses
  "What one criterion's run showed. Only `:status/pass` counts toward a
   passing verdict; `:status/unrun` and `:status/unsupported` never do."
  [:status/pass :status/fail :status/unrun :status/unsupported])

(def ^{:stratum 0} verdicts
  "`:verdict/incomplete` means no criterion failed but at least one did not
   pass: it was not run, or the scenario does not support it."
  [:verdict/pass :verdict/fail :verdict/incomplete])

(def ^{:stratum 0} failure-kinds
  "Why a scenario produced no usable results."
  [:scenario/threw :scenario/invalid-output])

(defn ^{:stratum 0} verdict-of-statuses
  "`:verdict/fail` when any status failed, `:verdict/pass` when every
   status passed, `:verdict/incomplete` otherwise."
  [statuses]
  (let [status-of (set statuses)]
    (cond
      (contains? status-of :status/fail) :verdict/fail
      (= #{:status/pass} status-of)      :verdict/pass
      :else                              :verdict/incomplete)))

(def ^{:stratum 0} NonBlank
  [:and :string [:fn (complement str/blank?)]])

(def ^{:stratum 0} Sha256Hex
  "Lowercase hex SHA-256, as `content-hash` produces."
  [:re #"\A[0-9a-f]{64}\z"])

(def ^{:stratum 0} GitSha
  [:re #"\A[0-9a-f]{40}\z"])
