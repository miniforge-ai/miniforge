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
(ns ai.miniforge.opsv.verification
  "Pure per-criterion N7 policy verification.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} evaluate-criterion
  [observations evaluate-fn criterion]
  (let [observed (get observations (:criterion/id criterion))
        {:keys [passed? reason-code]} (evaluate-fn criterion observed)]
    {:criterion/id (:criterion/id criterion)
     :criterion/passed? passed?
     :criterion/observed observed
     :criterion/expected (:criterion/expected criterion)
     :criterion/reason-code reason-code}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} verify-policy-impl
  "Verify validated inputs. The interface owns input and result validation."
  [{:keys [criteria observations evaluate-fn confidence caveats]}]
  (let [results (mapv #(evaluate-criterion observations evaluate-fn %)
                      criteria)]
    {:passed? (every? :criterion/passed? results)
     :criteria-evaluation results
     :confidence confidence
     :caveats caveats}))
