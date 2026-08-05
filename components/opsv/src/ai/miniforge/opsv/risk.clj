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
(ns ai.miniforge.opsv.risk
  "Pure, policy-transparent N7 operational risk assessment.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} score->level
  [score {:keys [medium high critical]}]
  (cond
    (>= score critical) :critical
    (>= score high) :high
    (>= score medium) :medium
    :else :low))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} assess-risk-impl
  "Assess validated explicit factors; the interface owns validation."
  [{:keys [factors level-thresholds]}]
  (let [score (min 1.0 (reduce + 0.0 (map :contribution factors)))]
    {:score score
     :level (score->level score level-thresholds)
     :factors factors}))
