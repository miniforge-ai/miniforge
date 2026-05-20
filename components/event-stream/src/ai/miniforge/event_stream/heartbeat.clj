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
   the scheduled task — no external locking required."
  (:require
   [ai.miniforge.event-stream.core :as core])
  (:import
   [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

;; ---------------------------------------------------------------------------
;; Constants

(def ^:const default-interval-ms
  "Default interval between heartbeat events: 30 seconds."
  30000)

;; ---------------------------------------------------------------------------
;; Private helpers

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
   (let [interval-ms (get opts :interval-ms default-interval-ms)
         seq-num     (atom 0)
         last-tick   (atom (System/currentTimeMillis))
         executor    (Executors/newSingleThreadScheduledExecutor)
         task        (make-heartbeat-task event-stream workflow-id phase-id
                                          seq-num last-tick)]
     (.scheduleAtFixedRate ^ScheduledExecutorService executor
                           task
                           interval-ms
                           interval-ms
                           TimeUnit/MILLISECONDS)
     {:heartbeat/executor    executor
      :heartbeat/phase-id    phase-id
      :heartbeat/last-tick   last-tick
      :heartbeat/seq-num     seq-num})))

(defn stop-heartbeat!
  "Stop a heartbeat scheduler returned by `start-heartbeat!`.

   Initiates an orderly shutdown: no new tasks are scheduled. Any
   in-flight emission is allowed to complete. Returns immediately
   without blocking.

   Safe to call with nil — no-op."
  [handle]
  (when-let [^ScheduledExecutorService executor (:heartbeat/executor handle)]
    (.shutdown executor)))

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
