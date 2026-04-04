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

(ns ai.miniforge.observer.interface-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.observer.interface :as observer]
   [ai.miniforge.workflow.interface.protocols.workflow-observer :as wf-proto]))

(defn sample-workflow-state
  [workflow-id status & {:keys [tokens cost duration]
                         :or {tokens 1000 cost 0.10 duration 5000}}]
  {:workflow/id workflow-id
   :workflow/status status
   :workflow/metrics {:tokens tokens
                      :cost-usd cost
                      :duration-ms duration}
   :workflow/history [{:from-phase :start
                       :to-phase :implement
                       :timestamp (java.util.Date.)}]
   :workflow/errors (when (= status :failed)
                     [{:phase :test :error "Test failed"}])})

(defn sample-phase-result
  [success? & {:keys [tokens cost duration errors]
               :or {tokens 500 cost 0.05 duration 2000 errors []}}]
  {:success? success?
   :metrics {:tokens tokens
             :cost-usd cost
             :duration-ms duration}
   :errors errors
   :artifacts []})

(deftest workflow-observer-integration-test
  (testing "observer implements WorkflowObserver protocol"
    (let [obs (observer/create-observer)
          workflow-id (random-uuid)]
      (wf-proto/on-phase-start obs workflow-id :implement {})
      (wf-proto/on-phase-complete obs workflow-id :implement (sample-phase-result true))
      (wf-proto/on-phase-start obs workflow-id :test {})
      (wf-proto/on-phase-complete obs workflow-id :test (sample-phase-result true))
      (wf-proto/on-workflow-complete obs workflow-id (sample-workflow-state workflow-id :completed))
      (let [metrics (observer/get-workflow-metrics obs workflow-id)]
        (is (some? metrics))
        (is (= 2 (count (:phases metrics))))
        (is (= :completed (:status metrics))))))

  (testing "observer handles phase errors"
    (let [obs (observer/create-observer)
          workflow-id (random-uuid)]
      (wf-proto/on-phase-error obs workflow-id :test {:message "Test failed" :type :assertion-error})
      (wf-proto/on-workflow-complete obs workflow-id (sample-workflow-state workflow-id :failed))
      (let [metrics (observer/get-workflow-metrics obs workflow-id)]
        (is (= :failed (:status metrics)))
        (is (seq (:phases metrics))))))

  (testing "observer handles rollback"
    (let [obs (observer/create-observer)
          workflow-id (random-uuid)]
      (wf-proto/on-rollback obs workflow-id :test :implement "Test failed, rolling back")
      (is true))))
