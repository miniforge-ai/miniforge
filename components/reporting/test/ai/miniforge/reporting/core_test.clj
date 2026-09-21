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
(ns ai.miniforge.reporting.core-test
  "Unit tests for reporting core helpers."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.reporting.core :as core]
   [ai.miniforge.reporting.protocol :as proto]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-safe-get-returns-value-on-success
  (testing "safe-get returns the fn result when no exception is thrown"
    (is (= 42 (core/safe-get (fn [] 42))))
    (is (= :ok (core/safe-get (constantly :ok))))))

(deftest ^{:stratum 0} test-safe-get-returns-nil-on-exception
  (testing "safe-get without logger returns nil when fn throws"
    (is (nil? (core/safe-get (fn [] (throw (Exception. "boom"))))))))

(deftest ^{:stratum 0} test-safe-get-with-logger-returns-nil-on-exception
  (testing "safe-get with logger returns nil when fn throws"
    (let [[logger _] (log/collecting-logger)]
      (is (nil? (core/safe-get logger (fn [] (throw (Exception. "boom")))))))))

(deftest ^{:stratum 0} test-safe-get-with-logger-emits-warn-on-exception
  (testing "safe-get emits :reporting/safe-get-error at WARN when logger is provided"
    (let [[logger entries] (log/collecting-logger)]
      (core/safe-get logger (fn [] (throw (Exception. "boom"))))
      (is (some #(and (= :reporting/safe-get-error (:log/event %))
                      (= :warn (:log/level %)))
                @entries)))))

(deftest ^{:stratum 0} test-safe-get-with-logger-returns-value-on-success
  (testing "safe-get with logger returns value and does not log when fn succeeds"
    (let [[logger entries] (log/collecting-logger)]
      (is (= 42 (core/safe-get logger (fn [] 42))))
      (is (empty? @entries)))))

(deftest ^{:stratum 0} test-safe-get-nil-logger-is-safe
  (testing "safe-get with nil logger behaves like no-logger variant"
    (is (nil? (core/safe-get nil (fn [] (throw (Exception. "boom"))))))
    (is (= 42 (core/safe-get nil (fn [] 42))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-poll-events-returns-seeded-events
  (testing "poll-events returns all events queued before the call"
    (let [service (core/create-reporting-service)
          sub-id  (proto/subscribe service [:topic] identity)
          sub     (get @(:subscriptions service) sub-id)
          queue   (:subscription/event-queue sub)
          e1      {:event/topic :topic :event/data "a"}
          e2      {:event/topic :topic :event/data "b"}]
      (swap! queue conj e1 e2)
      (is (= [e1 e2] (proto/poll-events service sub-id))))))

(deftest ^{:stratum 2} test-poll-events-clears-queue-after-drain
  (testing "poll-events drains the queue atomically — subsequent call returns empty"
    (let [service (core/create-reporting-service)
          sub-id  (proto/subscribe service [:topic] identity)
          sub     (get @(:subscriptions service) sub-id)
          queue   (:subscription/event-queue sub)
          event   {:event/topic :topic :event/data "x"}]
      (swap! queue conj event)
      (proto/poll-events service sub-id)
      (is (= [] (proto/poll-events service sub-id))))))

(deftest ^{:stratum 2} test-two-step-drain-loses-event-in-race-window
  (testing "non-atomic @queue/reset! loses an event injected between deref and reset"
    ;; Deterministic proof of the failure mode the swap-vals! fix addresses.
    ;; Reproduce the OLD poll-events drain sequence with a concurrent event
    ;; injected in the gap between @queue and (reset! queue []).
    (let [queue (atom [])
          e1    {:event/topic :topic :event/data "seeded"}
          e2    {:event/topic :topic :event/data "injected-in-race"}]
      (swap! queue conj e1)
      (let [drained @queue                 ; OLD step 1: non-atomic read
            _       (swap! queue conj e2)  ; concurrent write in the gap
            _       (reset! queue [])]     ; OLD step 2: e2 cleared, not returned
        (is (not (contains? (set drained) e2))
            "e2 was not captured by the non-atomic deref")
        (is (= [] @queue)
            "e2 was also erased by reset! — permanently lost")))))

(deftest ^{:stratum 2} test-swap-vals-drain-captures-event-written-during-cas-retry
  (testing "swap-vals! retries the CAS after a concurrent write, capturing the injected event"
    ;; Coordinate a concurrent enqueue inside the swap-vals! fn body.
    ;; swap-vals! detects the atom changed between fn execution and CAS commit
    ;; and retries, so the injected event is included in the returned old-value.
    ;; poll-events uses this mechanism; reverting to @queue/reset! would lose
    ;; the event proven missing in test-two-step-drain-loses-event-in-race-window.
    (let [queue      (atom [])
          e1         {:event/topic :topic :event/data "seeded"}
          e2         {:event/topic :topic :event/data "concurrent"}
          latch      (java.util.concurrent.CountDownLatch. 1)
          ready      (java.util.concurrent.CountDownLatch. 1)
          first-call (atom true)]
      (swap! queue conj e1)
      (let [injector (future
                       (.await latch)
                       (swap! queue conj e2)
                       (.countDown ready))]
        (let [[drained _] (swap-vals! queue
                            (fn [q]
                              (when @first-call
                                (reset! first-call false)
                                (.countDown latch) ; signal injector
                                (.await ready))    ; wait for e2 to land
                              []))]
          @injector
          (is (= [e1 e2] drained)
              "swap-vals! captured both events after CAS retry on the concurrent write")
          (is (= [] @queue) "queue is empty — no events remain"))))))
