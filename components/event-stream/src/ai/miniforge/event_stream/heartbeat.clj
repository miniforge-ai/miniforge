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

(ns ai.miniforge.event-stream.heartbeat
  "Phase heartbeat scheduler.

   Starts a background ScheduledExecutorService that emits
   :workflow/phase-heartbeat events at a configurable interval while a
   phase is running. Stops cleanly when the phase completes or errors.

   Thread safety: each call to start-heartbeat! creates a dedicated
   single-threaded ScheduledExecutorService. The sequence counter and
   last-tick timestamp are held in atoms and updated atomically inside
   the scheduled task — no external locking required.

   The backing thread is a NAMED DAEMON — see `manifest.clj` for the
   same pattern. Non-daemon threads block JVM shutdown if a heartbeat
   isn't stopped; named threads make `jstack`-level diagnosis painless."
  (:require
   [ai.miniforge.event-stream.core :as core])
  (:import
   [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture TimeUnit]
   [java.util.concurrent.atomic AtomicLong]))

;; ---------------------------------------------------------------------------
;; Constants

(def ^:const default-interval-ms
  "Default interval between heartbeat events: 30 seconds."
  30000)

(def ^:private heartbeat-thread-counter
  "Monotonic counter for naming heartbeat threads. Each scheduler gets
   a unique suffix so `jstack` output disambiguates concurrent phases."
  (AtomicLong. 0))

;; ---------------------------------------------------------------------------
;; Private helpers

(defn- heartbeat-thread-factory
  "Build a ThreadFactory that produces a single named daemon thread for
   one scheduler. Pattern mirrors `manifest.clj` — daemon so JVM
   shutdown isn't blocked by a still-running heartbeat, named with the
   phase id so `jstack` traces map back to the phase that owns the
   thread."
  [phase-id]
  (let [n (.incrementAndGet ^AtomicLong heartbeat-thread-counter)]
    (reify java.util.concurrent.ThreadFactory
      (newThread [_ r]
        (doto (Thread. r (str "miniforge-phase-heartbeat-"
                              (some-> phase-id name)
                              "-" n))
          (.setDaemon true))))))

(defn- make-heartbeat-task
  "Return a zero-arg fn suitable for ScheduledExecutorService that emits
   one :workflow/phase-heartbeat event per invocation and advances the
   shared counters.

   Catches all exceptions so a single bad emission cannot cancel the
   ScheduledFuture and silence all subsequent heartbeats."
  [event-stream workflow-id phase-id seq-num last-tick]
  (fn []
    (try
      (let [now    (System/currentTimeMillis)
            gap-ms (- now @last-tick)
            seq    (swap! seq-num inc)
            event  (-> (core/phase-heartbeat
                         event-stream workflow-id phase-id
                         {:phase/gap-since-last-event-ms gap-ms
                          :phase/last-event-at            (java.util.Date. ^long @last-tick)})
                       (assoc :heartbeat/phase-id              phase-id
                              :heartbeat/seq-in-phase           seq
                              :heartbeat/gap-since-last-event-ms gap-ms))]
        (reset! last-tick now)
        (core/publish! event-stream event))
      (catch Exception e
        ;; Log to stderr so the phase output stream is not polluted.
        (binding [*out* *err*]
          (println "phase-heartbeat emission error:" (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; Public API

(defn start-heartbeat!
  "Start a heartbeat scheduler for the given phase.

   Schedules a repeating task that emits `:workflow/phase-heartbeat`
   events every `interval-ms` milliseconds (default 30 000 ms).

   Each heartbeat event carries the following keys in addition to the
   standard event envelope fields:

     :heartbeat/phase-id              — the phase keyword passed as phase-id
     :heartbeat/seq-in-phase          — monotonically-increasing counter
                                        (1-based; first heartbeat is 1)
     :heartbeat/gap-since-last-event-ms — elapsed ms since the previous
                                        heartbeat (or phase start for the
                                        first one)

   The underlying phase-heartbeat envelope also carries:
     :phase/gap-since-last-event-ms   — same value as heartbeat/gap-since-last-event-ms
     :phase/last-event-at             — inst of the previous tick

   Returns an opaque handle map that MUST be passed to `stop-heartbeat!`
   when the phase ends (on success or error). Failing to call
   stop-heartbeat! leaks the backing executor thread.

   Arguments:
   - event-stream  event stream atom
   - workflow-id   UUID of the owning workflow
   - phase-id      keyword identifying the current phase (e.g. :implement)
   - opts          optional map — {:interval-ms long}"
  ([event-stream workflow-id phase-id]
   (start-heartbeat! event-stream workflow-id phase-id {}))
  ([event-stream workflow-id phase-id opts]
   (let [interval-ms (get opts :interval-ms default-interval-ms)]
     ;; Validate BEFORE creating the executor — scheduleAtFixedRate
     ;; throws when interval-ms ≤ 0, but the executor is already
     ;; alive at that point and would leak its thread.
     (when-not (and (integer? interval-ms) (pos? interval-ms))
       (throw (ex-info "start-heartbeat!: :interval-ms must be a positive integer"
                       {:interval-ms interval-ms
                        :phase-id    phase-id
                        :workflow-id workflow-id})))
     (let [seq-num     (atom 0)
           last-tick   (atom (System/currentTimeMillis))
           executor    (Executors/newSingleThreadScheduledExecutor
                         (heartbeat-thread-factory phase-id))
           task        (make-heartbeat-task event-stream workflow-id phase-id
                                            seq-num last-tick)
           ;; Schedule defensively — any failure between executor
           ;; creation and successful schedule shuts the executor
           ;; down rather than leaking a parked thread.
           future-handle
           (try
             (.scheduleAtFixedRate ^ScheduledExecutorService executor
                                   ^Runnable task
                                   ^long interval-ms
                                   ^long interval-ms
                                   TimeUnit/MILLISECONDS)
             (catch Throwable t
               (.shutdownNow ^ScheduledExecutorService executor)
               (throw t)))]
       ;; Retain the ScheduledFuture so stop-heartbeat! can cancel the
       ;; periodic task directly instead of relying on executor
       ;; shutdown semantics — cleaner lifecycle, no in-flight tick
       ;; allowed to keep ticking after the phase ended.
       {:heartbeat/executor   executor
        :heartbeat/future     future-handle
        :heartbeat/phase-id   phase-id
        :heartbeat/last-tick  last-tick
        :heartbeat/seq-num    seq-num}))))

(defn stop-heartbeat!
  "Stop a heartbeat scheduler returned by `start-heartbeat!`.

   Cancels the periodic ScheduledFuture directly (does NOT interrupt
   an in-flight tick — set `:may-interrupt? true` in opts to escalate),
   then shuts down the backing executor. Returns immediately without
   blocking.

   Tighter than the prior shape, which only shut the executor down
   and relied on the executor's continue-existing-tasks policy for
   the in-flight tick. Direct cancellation makes the stop semantics
   explicit and removes the dependence on JDK executor defaults.

   Safe to call with nil — no-op."
  ([handle]
   (stop-heartbeat! handle {}))
  ([handle {:keys [may-interrupt?] :or {may-interrupt? false}}]
   (when-let [^ScheduledFuture fut (:heartbeat/future handle)]
     (.cancel fut (boolean may-interrupt?)))
   (when-let [^ScheduledExecutorService executor (:heartbeat/executor handle)]
     (.shutdown executor))))

;; ---------------------------------------------------------------------------
;; Rich comment — REPL development examples

(comment
  (require '[ai.miniforge.event-stream.core :as core])

  ;; Start a fast heartbeat and watch events appear
  (def test-stream
    (core/create-event-stream
      {:sinks [(fn [evt]
                 (println "♥" (:heartbeat/seq-in-phase evt)
                          "gap:" (:heartbeat/gap-since-last-event-ms evt) "ms"))]}))

  (def handle
    (start-heartbeat! test-stream (random-uuid) :implement {:interval-ms 500}))

  ;; Stop after a few seconds
  (stop-heartbeat! handle))
