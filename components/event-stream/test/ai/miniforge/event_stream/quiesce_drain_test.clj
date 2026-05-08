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

(ns ai.miniforge.event-stream.quiesce-drain-test
  "Contract tests for the BD-2a shutdown ordering primitives.

   `quiesce!` fences future publishers for a workflow id; `drain!` waits
   for in-flight publishes to settle and asks each sink that exposes a
   drain hook (under metadata) to flush. Together they replace the
   pre-BD-2a race where headless exits could land before background
   producers finished publishing or sinks finished writing."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.core :as core]))

(defn- recording-sink
  "Return [sink record] where `sink` collects events into `record` so
   tests can assert sink reach-through."
  []
  (let [record (atom [])
        sink (fn [event] (swap! record conj event))]
    [sink record]))

(defn- workflow-event
  "Build a minimal event with `:workflow/id` for publish! tests. The
   envelope shape is what `create-envelope` would have produced; tests
   here construct it directly to avoid mutating sequence numbers."
  [workflow-id event-type]
  {:event/type event-type
   :event/id (random-uuid)
   :event/timestamp (java.util.Date.)
   :event/version core/event-version
   :workflow/id workflow-id
   :message "test"})

;------------------------------------------------------------------------------ quiesce!

(deftest quiesce-rejects-future-publishes-for-target-workflow
  (let [[sink record] (recording-sink)
        stream (core/create-event-stream {:sinks [sink]})
        wid (random-uuid)
        result-pre (core/publish! stream (workflow-event wid :test/event))
        _quiesce (core/quiesce! stream {:workflow-id wid})
        result-post (core/publish! stream (workflow-event wid :test/event))]
    (is (= 1 (count @record))
        "the sink should observe the pre-quiesce event but not the post-quiesce one")
    (is (not (:rejected? result-pre)))
    (is (true? (:rejected? result-post)))
    (is (= :workflow-quiesced (:reason result-post)))
    (is (= wid (:workflow-id result-post)))))

(deftest quiesce-only-fences-named-workflow
  (let [[sink record] (recording-sink)
        stream (core/create-event-stream {:sinks [sink]})
        wid-a (random-uuid)
        wid-b (random-uuid)]
    (core/quiesce! stream {:workflow-id wid-a})
    (let [a-result (core/publish! stream (workflow-event wid-a :test/event))
          b-result (core/publish! stream (workflow-event wid-b :test/event))]
      (is (true? (:rejected? a-result))
          "workflow A is quiesced and must reject")
      (is (not (:rejected? b-result))
          "workflow B is independent and must still publish")
      (is (= 1 (count @record))
          "only B's event reached the sink"))))

(deftest quiesce-without-workflow-id-just-waits
  ;; Without a target workflow, quiesce! adds no fence — it only waits
  ;; for any currently in-flight publish to settle. Useful as a global
  ;; barrier before drain! when no specific workflow is being torn down.
  (let [stream (core/create-event-stream {:sinks []})]
    (let [result (core/quiesce! stream {})]
      (is (true? (:ok? result)))
      (is (= 0 (:pending-publishers result))))
    (let [wid (random-uuid)
          ;; And publish still works after a no-target quiesce.
          publish-result (core/publish! stream (workflow-event wid :test/event))]
      (is (not (:rejected? publish-result))))))

(deftest quiesce-returns-ok-when-stream-is-quiet
  (let [stream (core/create-event-stream {:sinks []})
        result (core/quiesce! stream {:workflow-id (random-uuid) :timeout-ms 500})]
    (is (true? (:ok? result)))
    (is (= 0 (:pending-publishers result)))))

;------------------------------------------------------------------------------ drain!

(deftest drain-default-sinks-return-ok-immediately
  ;; File / stdout / stderr sinks are synchronous in the publish! body, so
  ;; once publish! returns there is nothing to drain. Sinks without a
  ;; metadata `:drain` hook are assumed already-drained.
  (let [[sink _record] (recording-sink)
        stream (core/create-event-stream {:sinks [sink]})
        wid (random-uuid)]
    (dotimes [_ 5]
      (core/publish! stream (workflow-event wid :test/event)))
    (let [result (core/drain! stream {:timeout-ms 500})]
      (is (true? (:ok? result)))
      (is (= 1 (:drained-count result))))))

(deftest drain-invokes-sink-drain-hook-when-present
  ;; A sink that carries a drain hook under metadata (e.g. fleet-sink with
  ;; an internal batch) must receive the drain call and report status.
  (let [drain-called? (atom false)
        sink (with-meta
               (fn [_event] nil)
               {:drain (fn [_opts]
                         (reset! drain-called? true)
                         {:ok? true})})
        stream (core/create-event-stream {:sinks [sink]})
        result (core/drain! stream {:timeout-ms 500})]
    (is (true? @drain-called?))
    (is (true? (:ok? result)))))

(deftest drain-reports-sink-error-with-structured-result
  (let [bad-sink (with-meta
                   (fn [_event] nil)
                   {:drain (fn [_opts] {:ok? false :reason :sink-error :error "boom"})})
        good-sink (with-meta
                    (fn [_event] nil)
                    {:drain (fn [_opts] {:ok? true})})
        stream (core/create-event-stream {:sinks [good-sink bad-sink]})
        result (core/drain! stream {:timeout-ms 500})]
    (is (false? (:ok? result)))
    (is (= :sink-error (:reason result)))
    (is (= 1 (count (:failed-sinks result))))
    (is (= "boom" (-> result :failed-sinks first :error)))))

(deftest drain-reports-timeout-when-sink-hook-hangs
  (let [hanging-sink (with-meta
                       (fn [_event] nil)
                       {:drain (fn [_opts]
                                 ;; Sink itself reports timeout when its budget runs out.
                                 (Thread/sleep 200)
                                 {:ok? false :reason :timeout})})
        stream (core/create-event-stream {:sinks [hanging-sink]})
        result (core/drain! stream {:timeout-ms 250})]
    (is (false? (:ok? result)))
    (is (= :sink-error (:reason result))
        "sink-reported timeout surfaces as a sink-error in the aggregate result")))

(deftest drain-catches-sink-drain-exceptions
  (let [throwing-sink (with-meta
                        (fn [_event] nil)
                        {:drain (fn [_opts] (throw (ex-info "kaboom" {})))})
        stream (core/create-event-stream {:sinks [throwing-sink]})
        result (core/drain! stream {:timeout-ms 500})]
    (is (false? (:ok? result)))
    (is (= :sink-error (:reason result)))
    (is (= "kaboom" (-> result :failed-sinks first :error)))))

;------------------------------------------------------------------------------ ordering invariants

(deftest publish-during-drain-does-not-bypass-quiesce
  ;; Pin the contract that quiesce! prevents publishers from sneaking in
  ;; between quiesce! and drain!. Without the quiesce step, drain! cannot
  ;; cover late publishers — that's the v1 race the BD-2a primitives are
  ;; meant to close.
  (let [[sink record] (recording-sink)
        stream (core/create-event-stream {:sinks [sink]})
        wid (random-uuid)]
    ;; Pre-quiesce publish lands.
    (core/publish! stream (workflow-event wid :test/event))
    (core/quiesce! stream {:workflow-id wid})
    ;; A publisher that races with shutdown attempts to publish *after*
    ;; quiesce. Drain runs immediately after.
    (core/publish! stream (workflow-event wid :test/event))
    (core/drain! stream {:timeout-ms 500})
    (is (= 1 (count @record))
        "exactly one event reached the sink — the pre-quiesce one")))

(deftest in-flight-tracking-decrements-on-sink-exception
  ;; A sink that throws must still release the in-flight slot, otherwise
  ;; quiesce! and drain! would wait forever on a stuck counter.
  (let [throwing-sink (fn [_event] (throw (RuntimeException. "sink boom")))
        stream (core/create-event-stream {:sinks [throwing-sink]})
        wid (random-uuid)]
    ;; publish! catches sink exceptions and continues.
    (core/publish! stream (workflow-event wid :test/event))
    (let [result (core/drain! stream {:timeout-ms 500})]
      (is (true? (:ok? result))
          "in-flight counter must release even when the sink throws"))))
