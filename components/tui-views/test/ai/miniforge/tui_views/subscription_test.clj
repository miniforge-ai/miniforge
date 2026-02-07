;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.tui-views.subscription-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.subscription :as sub]
   [ai.miniforge.event-stream.interface :as es]))

(deftest translate-workflow-events-test
  (testing "Workflow started translates correctly"
    (let [stream (es/create-event-stream)
          messages (atom [])
          wf-id (random-uuid)
          cleanup (sub/subscribe-to-stream! stream
                                            (fn [msg] (swap! messages conj msg))
                                            {:throttle-ms 5000})]
      (es/publish! stream (es/workflow-started stream wf-id {:name "test-wf"}))
      (Thread/sleep 50)
      (is (= 1 (count @messages)))
      (is (= :msg/workflow-added (first (first @messages))))
      (is (= wf-id (get-in (first @messages) [1 :workflow-id])))
      (cleanup))))

(deftest translate-phase-events-test
  (testing "Phase started translates correctly"
    (let [stream (es/create-event-stream)
          messages (atom [])
          wf-id (random-uuid)
          cleanup (sub/subscribe-to-stream! stream
                                            (fn [msg] (swap! messages conj msg))
                                            {:throttle-ms 5000})]
      (es/publish! stream (es/phase-started stream wf-id :plan))
      (Thread/sleep 50)
      (is (= :msg/phase-changed (first (first @messages))))
      (is (= :plan (get-in (first @messages) [1 :phase])))
      (cleanup))))

(deftest translate-workflow-completed-test
  (testing "Workflow completed translates correctly"
    (let [stream (es/create-event-stream)
          messages (atom [])
          wf-id (random-uuid)
          cleanup (sub/subscribe-to-stream! stream
                                            (fn [msg] (swap! messages conj msg))
                                            {:throttle-ms 5000})]
      (es/publish! stream (es/workflow-completed stream wf-id :success 10000))
      (Thread/sleep 50)
      (is (= :msg/workflow-done (first (first @messages))))
      (is (= :success (get-in (first @messages) [1 :status])))
      (cleanup))))

(deftest chunk-throttling-test
  (testing "Rapid chunks are coalesced"
    (let [stream (es/create-event-stream)
          messages (atom [])
          wf-id (random-uuid)
          cleanup (sub/subscribe-to-stream! stream
                                            (fn [msg] (swap! messages conj msg))
                                            {:throttle-ms 100})]
      ;; Send many chunks rapidly
      (dotimes [i 10]
        (es/publish! stream (es/agent-chunk stream wf-id :planner (str "chunk-" i " "))))
      ;; Wait for aggregation flush
      (Thread/sleep 250)
      ;; Should have fewer messages than 10 (coalesced)
      (let [chunk-msgs (filter #(= :msg/agent-output (first %)) @messages)]
        (is (< (count chunk-msgs) 10)))
      (cleanup))))

(deftest cleanup-unsubscribes-test
  (testing "Cleanup function stops the subscription"
    (let [stream (es/create-event-stream)
          messages (atom [])
          wf-id (random-uuid)
          cleanup (sub/subscribe-to-stream! stream
                                            (fn [msg] (swap! messages conj msg))
                                            {:throttle-ms 5000})]
      (cleanup)
      (Thread/sleep 50)
      ;; Events after cleanup should not be received
      (reset! messages [])
      (es/publish! stream (es/workflow-started stream wf-id {:name "test"}))
      (Thread/sleep 50)
      (is (empty? @messages)))))
