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
(ns ai.miniforge.workflow.runner-iteration-control-test
  "Operator control at the runner's phase boundary: a run paused and then
   cancelled must leave the pause wait and take the stop path."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [ai.miniforge.workflow.checkpoint-test-support :as checkpoint-test-support]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.workflow.phase-test-support :as phase-test-support]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.workflow.runner-defaults :as defaults]))

(use-fixtures :each
  phase-test-support/with-workflow-phase-test-support
  checkpoint-test-support/with-temp-checkpoint-root)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private stop-wait-ms
  "Bound on how long the test waits for the iteration to return after the
   stop. Many poll intervals, so only a wait that ignores :stopped misses it."
  5000)

(defn- ^{:stratum 0} paused-control-state
  []
  (atom {:paused true :stopped false :adjustments {}}))

(defn- ^{:stratum 0} test-context
  []
  (ctx/create-context {:workflow/id :test
                       :workflow/version "1.0.0"
                       :workflow/pipeline [{:phase phase-test-support/runner-test-done}]}
                      {:task "Test"}
                      {}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} paused-then-stopped-iteration-takes-stop-path-test
  (testing "a stop issued during a pause ends the wait without a resume"
    (with-redefs [defaults/pause-poll-interval-ms (constantly 10)]
      (let [control-state (paused-control-state)
            iteration (future
                        (runner/execute-single-iteration
                         [] (test-context) {} 1 control-state))]
        (try
          ;; Let the iteration enter the pause wait before the stop lands.
          (Thread/sleep 100)
          (is (not (realized? iteration)) "the pause holds the iteration")
          (swap! control-state assoc :stopped true)
          (let [result (deref iteration stop-wait-ms ::still-waiting)]
            (is (not= ::still-waiting result)
                "the wait ends on :stopped while :paused is still true")
            (when (map? result)
              (is (= :failed (:execution/status result)))
              (is (some #(= :dashboard-stop (:type %))
                        (:execution/errors result)))))
          (finally
            ;; Release a wait that ignored :stopped so no thread outlives the test.
            (swap! control-state assoc :paused false)
            (deref iteration stop-wait-ms nil)))))))
