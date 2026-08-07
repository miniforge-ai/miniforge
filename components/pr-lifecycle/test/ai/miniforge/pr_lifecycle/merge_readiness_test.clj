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
(ns ai.miniforge.pr-lifecycle.merge-readiness-test
  "Behavior locks for provider-backed merge-readiness classification."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.merge :as merge]
   [ai.miniforge.pr-lifecycle.merge-readiness :as readiness]
   [clojure.test :refer [are deftest testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private passing-operations
  {:check-ci (constantly (dag/ok {:ci-green? true}))
   :check-review (constantly (dag/ok {:approved? true}))
   :check-branch (constantly (dag/ok {:up-to-date? true}))
   :check-threads (constantly (dag/ok {:has-unresolved? false}))})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} provider-failures-are-classified-precisely
  (testing "Every provider outage blocks with its own reason"
    (are [operation reason]
         (let [operations (assoc passing-operations operation
                                 (constantly (dag/err :provider-error
                                                      "unavailable")))
               result (readiness/evaluate
                       operations "/tmp" 123 merge/default-merge-policy)]
           (= [reason] (:blocking result)))
      :check-ci :ci-status-unavailable
      :check-review :review-status-unavailable
      :check-branch :branch-status-unavailable
      :check-threads :thread-status-unavailable)))
