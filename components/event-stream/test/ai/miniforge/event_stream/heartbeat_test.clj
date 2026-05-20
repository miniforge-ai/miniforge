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
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.heartbeat :as heartbeat])
  (:import
   [java.util.concurrent ScheduledExecutorService TimeUnit]))

(defn- collecting-stream []
  (let [events (atom [])]
    {:events events
     :stream (core/create-event-stream
               {:sinks [(fn [event] (swap! events conj event))]})}))

(defn- wait-for-events [events n]
  (let [deadline (+ (System/currentTimeMillis) 500)]
    (loop []
      (if (or (>= (count @events) n)
              (>= (System/currentTimeMillis) deadline))
        @events
        (do (Thread/sleep 10)
            (recur))))))

(deftest start-heartbeat!-returns-handle
  (let [{:keys [stream]} (collecting-stream)
        handle (heartbeat/start-heartbeat! stream (random-uuid) :plan
                                           {:interval-ms 5000})]
    (try
      (is (instance? ScheduledExecutorService (:heartbeat/executor handle)))
      (is (= :plan (:heartbeat/phase-id handle)))
      (is (instance? clojure.lang.Atom (:heartbeat/last-tick handle)))
      (is (instance? clojure.lang.Atom (:heartbeat/seq-num handle)))
      (finally
        (heartbeat/stop-heartbeat! handle)))))

(deftest heartbeat-emits-phase-liveness-events
  (testing "heartbeat events carry workflow, phase, gap, and monotonic sequence"
    (let [{:keys [stream events]} (collecting-stream)
          workflow-id (random-uuid)
          handle (heartbeat/start-heartbeat! stream workflow-id :implement
                                             {:interval-ms 25})]
      (try
        (let [published (wait-for-events events 2)
              seqs (mapv :heartbeat/seq-in-phase published)]
          (is (<= 2 (count published)))
          (is (every? #(= :workflow/phase-heartbeat (:event/type %)) published))
          (is (every? #(= workflow-id (:workflow/id %)) published))
          (is (every? #(= :implement (:heartbeat/phase-id %)) published))
          (is (every? #(inst? (:phase/active-since %)) published))
          (is (= seqs (mapv :phase/events-emitted published)))
          (is (every? #(nat-int? (:heartbeat/gap-since-last-event-ms %)) published))
          (is (= (range 1 (inc (count seqs))) seqs)))
        (finally
          (heartbeat/stop-heartbeat! handle))))))

(deftest stop-heartbeat!-halts-emissions
  (let [{:keys [stream events]} (collecting-stream)
        handle (heartbeat/start-heartbeat! stream (random-uuid) :test
                                           {:interval-ms 25})]
    (wait-for-events events 1)
    (heartbeat/stop-heartbeat! handle)
    (is (.awaitTermination ^ScheduledExecutorService (:heartbeat/executor handle)
                           200 TimeUnit/MILLISECONDS))
    (let [count-at-stop (count @events)]
      (Thread/sleep 75)
      (is (= count-at-stop (count @events))))))

(deftest stop-heartbeat!-is-safe
  (is (nil? (heartbeat/stop-heartbeat! nil)))
  (let [{:keys [stream]} (collecting-stream)
        handle (heartbeat/start-heartbeat! stream (random-uuid) :plan)]
    (heartbeat/stop-heartbeat! handle)
    (is (nil? (heartbeat/stop-heartbeat! handle)))))

(deftest start-heartbeat!-rejects-invalid-interval
  (let [{:keys [stream]} (collecting-stream)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"positive integer"
                          (heartbeat/start-heartbeat! stream (random-uuid) :plan
                                                      {:interval-ms 0})))))
