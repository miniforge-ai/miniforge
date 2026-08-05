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
(ns ai.miniforge.opsv.convergence
  "Pure bounded convergence with explicit N7 terminal conditions.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} stabilized?
  [{:keys [minimum-measurement-window-seconds required-repetitions]}
   {:keys [measurement-window-seconds repetitions]}]
  (and (>= measurement-window-seconds minimum-measurement-window-seconds)
       (>= repetitions required-repetitions)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} terminal-reason
  [config evaluation iteration]
  (let [{:keys [guardrail-abort? success-criteria-satisfied?
                headroom-satisfied? confidence]} evaluation
        stable? (stabilized? config evaluation)]
    (cond
      guardrail-abort? :guardrail-abort
      (and stable? success-criteria-satisfied? headroom-satisfied?)
      :success-criteria-satisfied
      (and stable? (>= confidence (:confidence-threshold config)))
      :confidence-threshold-reached
      (>= iteration (:iteration-limit config)) :iteration-limit-reached)))
