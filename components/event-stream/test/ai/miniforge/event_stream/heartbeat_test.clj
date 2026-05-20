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

(ns ai.miniforge.event-stream.heartbeat-test
  "Unit tests for the phase heartbeat scheduler.

   All tests use a very short interval (≤ 50 ms) so assertions run in
   well under 300 ms on slow CI. Each test stops the scheduler in a
   `finally` block to prevent executor leaks across test runs."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.heartbeat :as heartbeat]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- collecting-stream
  "Return {:stream stream :collected atom} wired to a vector-accumulating sink."
  []
  (let [collected (atom [])
        sink      (fn [event] (swap! collected conj event))
        stream    (core/create-event-stream {:sinks [sink]})]
    {:stream stream :collected collected}))

(defn- wait-for-n-events
  "Block until at least `n` events appear in `collected` or `timeout-ms` elapses.
   Returns the final event list."
  [collected n timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [events @collected]
        (if (or (>= (count events) n)
                (>= (System/currentTimeMillis) deadline))
          events
          (do (Thread/sleep 10)
              (recur)))))))

;; ---------------------------------------------------------------------------
;; Handle shape

(deftest start-heartbeat!-returns-handle
  (testing "handle is a map with expected keys"
    (let [{:keys [stream]} (collecting-stream)
          workflow-id      (random-uuid)
          handle           (heartbeat/start-heartbeat! stream workflow-id :plan
                                                       {:interval-ms 5000})]
      (try
        (is (map? handle))
        (is (some? (:heartbeat/executor handle)))
        (is (= :plan (:heartbeat/phase-id handle)))
        (is (instance? clojure.lang.Atom (:heartbeat/last-tick handle)))
        (is (instance? clojure.lang.Atom (:heartbeat/seq-num handle)))
        (finally
          (heartbeat/stop-heartbeat! handle))))))

;; ---------------------------------------------------------------------------
;; Event shape

(deftest heartbeat-emits-correct-event-type
  (testing "emitted events have :workflow/phase-heartbeat type"
    (let [{:keys [stream collected]} (collecting-stream)
          workflow-id                (random-uuid)
          handle                     (heartbeat/start-heartbeat! stream workflow-id :implement
                                                                 {:interval-ms 30})]
      (try
        (wait-for-n-events collected 1 500)
        (let [events @collected]
          (is (seq events))
          (is (every? #(= :workflow/phase-heartbeat (:event/type %)) events)))
        (finally
          (heartbeat/stop-heartbeat! handle))))))

(deftest heartbeat-carries-required-fields
  (testing "each heartbeat event carries :heartbeat/* fields"
    (let [{:keys [stream collected]} (collecting-stream)
          workflow-id                (random-uuid)
          handle                     (heartbeat/start-heartbeat! stream workflow-id :plan
                                                                 {:interval-ms 30})]
      (try
        (wait-for-n-events collected 1 500)
        (let [evt (first @collected)]
          (is (= :plan (:heartbeat/phase-id evt))
              ":heartbeat/phase-id must match the phase keyword")
          (is (pos-int? (:heartbeat/seq-in-phase evt))
              ":heartbeat/seq-in-phase must be a positive integer")
          (is (nat-int? (:heartbeat/gap-since-last-event-ms evt))
              ":heartbeat/gap-since-last-event-ms must be a non-negative integer")
          ;; Standard phase-heartbeat envelope fields also present
          (is (nat-int? (:phase/gap-since-last-event-ms evt))
              ":phase/gap-since-last-event-ms (envelope field) must be present")
          (is (inst? (:phase/last-event-at evt))
              ":phase/last-event-at must be an inst"))
        (finally
          (heartbeat/stop-heartbeat! handle))))))

(deftest heartbeat-carries-workflow-id
  (testing "each event is associated to the given workflow-id"
    (let [{:keys [stream collected]} (collecting-stream)
          workflow-id                (random-uuid)
          handle                     (heartbeat/start-heartbeat! stream workflow-id :release
                                                                 {:interval-ms 30})]
      (try
        (wait-for-n-events collected 1 500)
        (let [evt (first @collected)]
          (is (= workflow-id (:workflow/id evt))))
        (finally
          (heartbeat/stop-heartbeat! handle))))))

;; ---------------------------------------------------------------------------
;; Sequence numbering

(deftest heartbeat-seq-increments-monotonically
  (testing "seq-in-phase increments by 1 per emission"
    (let [{:keys [stream collected]} (collecting-stream)
          workflow-id                (random-uuid)
          handle                     (heartbeat/start-heartbeat! stream workflow-id :plan
                                                                 {:interval-ms 30})]
      (try
        (wait-for-n-events collected 3 600)
        (let [seqs (mapv :heartbeat/seq-in-phase @collected)]
          (is (>= (count seqs) 3))
          (is (= (range 1 (inc (count seqs))) seqs)
              "Sequence numbers must start at 1 and increment by 1"))
        (finally
          (heartbeat/stop-heartbeat! handle))))))

;; ---------------------------------------------------------------------------
;; Multiple emissions

(deftest heartbeat-emits-multiple-events
  (testing "at least 2 events are emitted within 2× interval"
    (let [{:keys [stream collected]} (collecting-stream)
          workflow-id                (random-uuid)
          handle                     (heartbeat/start-heartbeat! stream workflow-id :implement
                                                                 {:interval-ms 40})]
      (try
        (wait-for-n-events collected 2 600)
        (is (>= (count @collected) 2))
        (finally
          (heartbeat/stop-heartbeat! handle))))))

;; ---------------------------------------------------------------------------
;; Stop behaviour

(deftest stop-heartbeat!-halts-emissions
  (testing "no new events are emitted after stop-heartbeat!"
    (let [{:keys [stream collected]} (collecting-stream)
          workflow-id                (random-uuid)
          handle                     (heartbeat/start-heartbeat! stream workflow-id :test
                                                                 {:interval-ms 40})]
      (wait-for-n-events collected 1 500)
      (heartbeat/stop-heartbeat! handle)
      ;; Drain any in-flight event then snapshot
      (Thread/sleep 20)
      (let [count-at-stop (count @collected)]
        ;; Wait 2× the interval; count must not exceed count-at-stop by more than 1
        (Thread/sleep 100)
        (is (<= (count @collected) (inc count-at-stop))
            "No new events should be emitted after stop")))))

(deftest stop-heartbeat!-safe-with-nil
  (testing "stop-heartbeat! with nil handle does not throw"
    (is (nil? (heartbeat/stop-heartbeat! nil)))))

(deftest stop-heartbeat!-idempotent
  (testing "calling stop-heartbeat! twice does not throw"
    (let [{:keys [stream]} (collecting-stream)
          workflow-id      (random-uuid)
          handle           (heartbeat/start-heartbeat! stream workflow-id :plan
                                                       {:interval-ms 5000})]
      (heartbeat/stop-heartbeat! handle)
      (is (nil? (heartbeat/stop-heartbeat! handle))
          "Second stop-heartbeat! must not throw"))))

;; ---------------------------------------------------------------------------
;; Default interval arity

(deftest default-interval-arity
  (testing "start-heartbeat! with no opts uses default interval"
    (let [{:keys [stream]} (collecting-stream)
          workflow-id      (random-uuid)
          handle           (heartbeat/start-heartbeat! stream workflow-id :plan)]
      (try
        (is (map? handle) "should return a handle even with no opts")
        (finally
          (heartbeat/stop-heartbeat! handle))))))
