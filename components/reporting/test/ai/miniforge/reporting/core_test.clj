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

(deftest ^{:stratum 2} test-poll-events-atomic-drain-no-loss-under-concurrent-enqueue
  (testing "poll-events captures an event enqueued during the drain — none lost"
    ;; An atom validator fires on every proposed transition to [].
    ;; swap-vals! detects the concurrent write and retries, capturing e2 in the
    ;; returned old-value; @queue/reset! unconditionally overwrites to [] after the
    ;; validator fires, permanently losing e2.
    (let [injected? (atom false)
          callbacks  (atom [])
          e1         {:event/topic :topic :event/data "seeded"}
          e2         {:event/topic :topic :event/data "injected-during-drain"}
          service    (core/create-reporting-service)
          sub-id     (proto/subscribe service [:topic] #(swap! callbacks conj %))
          sub        (get @(:subscriptions service) sub-id)
          queue      (:subscription/event-queue sub)]
      (try
        (swap! queue conj e1)
        (set-validator! queue
          (fn [new-value]
            (when (and (empty? new-value)
                       (compare-and-set! injected? false true))
              (swap! queue conj e2))
            true))
        (let [round-1 (proto/poll-events service sub-id)
              round-2 (proto/poll-events service sub-id)]
          (is (= [e1 e2] (into round-1 round-2))
              "both events captured — none lost to a non-atomic drain")
          (is (= [e1 e2] @callbacks)
              "callback invoked for every captured event in order"))
        (finally
          (set-validator! queue nil))))))
