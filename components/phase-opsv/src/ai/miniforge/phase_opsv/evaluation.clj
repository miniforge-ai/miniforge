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
(ns ai.miniforge.phase-opsv.evaluation
  "Pure convergence and verification callbacks for OPSV application flow.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} convergence-step
  [state iteration _config]
  (let [steps (:steps state)
        step-index (min (dec iteration) (dec (count steps)))
        selected-step (nth steps step-index)]
    (-> state
        (assoc :selected-step selected-step)
        (update :history conj selected-step))))

(defn ^{:stratum 0} convergence-evaluation
  [state _iteration _config]
  (let [metrics (get-in state [:selected-step :step/metrics])]
    {:success-criteria-satisfied? (true? (:slo-passed? metrics))
     :headroom-satisfied? (true? (:headroom? metrics))
     :guardrail-abort? (true? (:guardrail-abort? metrics))
     :confidence (:confidence metrics)
     :measurement-window-seconds (:measurement-window-seconds metrics)
     :repetitions (:repetitions metrics)}))

(defn- ^{:stratum 0} criterion-passed?
  [observed expected]
  (if (and (number? observed) (number? expected))
    (<= (double observed) (double expected))
    (= observed expected)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} criterion-evaluation
  [criterion observed]
  (let [expected (:criterion/expected criterion)
        passed? (criterion-passed? observed expected)]
    {:passed? passed?
     :reason-code (if passed? :criterion/satisfied :criterion/exceeded)}))
