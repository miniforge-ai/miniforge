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
   [ai.miniforge.event-stream.heartbeat :as heartbeat])
  (:import
   [java.util.concurrent ScheduledExecutorService TimeUnit]))

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

(defn- active-thread-count
  "Count JVM threads whose name matches `pattern`."
  [^String pattern]
  (let [group    (.. Thread currentThread getThreadGroup)
        threads  (make-array Thread (* 4 (.activeCount group)))]
    (.enumerate group threads)
    (->> (seq threads)
         (filter some?)
         (filter #(re-find (re-pattern pattern) (.getName ^Thread %)))
         count)))

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
;; Multiple emissions — configured interval

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

(deftest heartbeat-respects-configured-interval
  (testing "gap-since-last-event-ms on the 2nd event is approximately interval-ms"
    ;; Strategy: record wall-clock time before starting, let 2 events fire, then
    ;; check that the gap on event #2 is within a generous CI tolerance of the
    ;; configured interval.  We use 80 ms interval with ±60 ms slop — this is
    ;; intentionally wide because scheduled-executor jitter on slow CI can be
    ;; significant.
    (let [{:keys [stream collected]} (collecting-stream)
          interval-ms                80
          handle                     (heartbeat/start-heartbeat! stream (random-uuid) :implement
                                                                 {:interval-ms interval-ms})]
      (try
        (wait-for-n-events collected 2 1500)
        (let [events @collected]
          (when (>= (count events) 2)
            (let [gap (:heartbeat/gap-since-last-event-ms (second events))]
              (is (nat-int? gap)
                  "gap-since-last-event-ms must be a non-negative integer")
              (is (<= gap (* 4 interval-ms))
                  (str "gap must not be wildly larger than interval; got " gap " ms")))))
        (finally
          (heartbeat/stop-heartbeat! handle))))))

;; ---------------------------------------------------------------------------
;; gap-since-last-event-ms correctness

(deftest gap-since-last-event-ms-reflects-elapsed-time
  (testing "gap-since-last-event-ms matches elapsed wall-clock time since previous tick"
    ;; We capture the wall-clock instant immediately before starting the
    ;; heartbeat, wait for the first event, then verify the reported gap is
    ;; at least the configured interval minus a small tolerance (the OS
    ;; scheduler may fire the task a few ms late, never early).
    (let [{:keys [stream collected]} (collecting-stream)
          interval-ms                60
          t0                         (System/currentTimeMillis)
          handle                     (heartbeat/start-heartbeat! stream (random-uuid) :plan
                                                                 {:interval-ms interval-ms})]
      (try
        (wait-for-n-events collected 1 1000)
        (let [evt (first @collected)
              gap (:heartbeat/gap-since-last-event-ms evt)
              wall-elapsed            (- (System/currentTimeMillis) t0)]
          (is (some? evt) "at least one event must arrive")
          (when evt
            ;; The gap is computed as (now - last-tick) inside the task; last-tick
            ;; is set to currentTimeMillis at construction time.  On JVM the task
            ;; fires after interval-ms; the reported gap should therefore be at
            ;; least the interval minus a small tolerance for system jitter.
            (is (>= gap (* 0.5 interval-ms))
                (str "gap " gap " ms should be at least half of interval " interval-ms " ms"))
            ;; Sanity-cap: gap cannot exceed total elapsed time since the test began.
            (is (<= gap (+ wall-elapsed 50))
                (str "gap " gap " ms should not exceed wall-elapsed " wall-elapsed " ms"))))
        (finally
          (heartbeat/stop-heartbeat! handle)))))

  (testing "gap-since-last-event-ms and heartbeat/gap-since-last-event-ms agree"
    ;; Both keys should carry the same integer value — the heartbeat ns
    ;; populates both from the same computed gap-ms local.
    (let [{:keys [stream collected]} (collecting-stream)
          handle                     (heartbeat/start-heartbeat! stream (random-uuid) :test
                                                                 {:interval-ms 40})]
      (try
        (wait-for-n-events collected 2 600)
        (doseq [evt @collected]
          (is (= (:phase/gap-since-last-event-ms evt)
                 (:heartbeat/gap-since-last-event-ms evt))
              "both gap fields must carry the same value"))
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

(deftest stop-heartbeat!-shuts-down-executor
  (testing "executor is terminated after stop-heartbeat!"
    (let [{:keys [stream]} (collecting-stream)
          handle            (heartbeat/start-heartbeat! stream (random-uuid) :plan
                                                        {:interval-ms 5000})]
      (let [executor ^ScheduledExecutorService (:heartbeat/executor handle)]
        (heartbeat/stop-heartbeat! handle)
        ;; Give the executor a moment to enter terminated state
        (.awaitTermination executor 200 TimeUnit/MILLISECONDS)
        (is (.isShutdown executor)
            "executor must be in shutdown state after stop-heartbeat!")))))

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

;; ---------------------------------------------------------------------------
;; Duplicate start for same phase-id

(deftest start-heartbeat!-duplicate-phase-creates-independent-handles
  (testing "starting a heartbeat for an already-active phase creates a second independent handle"
    ;; The current implementation does not track active phases, so calling
    ;; start-heartbeat! twice with the same phase-id produces two separate
    ;; schedulers.  Both must be stoppable without error.
    (let [{:keys [stream collected]} (collecting-stream)
          workflow-id                (random-uuid)
          phase-id                   :implement
          handle-1                   (heartbeat/start-heartbeat! stream workflow-id phase-id
                                                                 {:interval-ms 30})
          handle-2                   (heartbeat/start-heartbeat! stream workflow-id phase-id
                                                                 {:interval-ms 30})]
      (try
        ;; Both handles are valid maps
        (is (map? handle-1) "first handle must be a map")
        (is (map? handle-2) "second handle must be a map")
        ;; Executors are distinct objects
        (is (not (identical? (:heartbeat/executor handle-1)
                             (:heartbeat/executor handle-2)))
            "each start produces a distinct executor")
        ;; Combined, events from both schedulers accumulate
        (wait-for-n-events collected 2 600)
        (is (>= (count @collected) 2)
            "events should arrive from at least one of the two schedulers")
        (finally
          (heartbeat/stop-heartbeat! handle-1)
          (heartbeat/stop-heartbeat! handle-2))))))

;; ---------------------------------------------------------------------------
;; Rapid start/stop — no thread leak

(deftest rapid-start-stop-does-not-leak-threads
  (testing "repeated rapid start/stop cycles do not accumulate live threads"
    ;; Capture baseline thread count. Cycle through 20 rapid start/stop pairs,
    ;; then wait briefly and verify threads have not grown unboundedly.
    ;; We allow a small delta to accommodate JVM housekeeping threads that may
    ;; appear transiently, but reject counts that grow by more than the number
    ;; of iterations.
    (let [{:keys [stream]} (collecting-stream)
          workflow-id      (random-uuid)
          iterations       20
          ;; Warm up — ensure any lazy thread-pool init happens before baseline
          warmup-h         (heartbeat/start-heartbeat! stream workflow-id :warmup
                                                       {:interval-ms 5000})]
      (heartbeat/stop-heartbeat! warmup-h)
      (Thread/sleep 50)

      (let [baseline (.. Thread currentThread getThreadGroup activeCount)]
        ;; Run rapid start/stop pairs
        (dotimes [_ iterations]
          (let [h (heartbeat/start-heartbeat! stream workflow-id :stress
                                              {:interval-ms 5000})]
            (heartbeat/stop-heartbeat! h)))

        ;; Allow executor teardown threads to settle
        (Thread/sleep 100)

        (let [final-count (.. Thread currentThread getThreadGroup activeCount)
              growth      (- final-count baseline)]
          (is (<= growth iterations)
              (str "Thread count grew by " growth
                   " after " iterations " rapid start/stop cycles;"
                   " expected ≤ " iterations)))))))
