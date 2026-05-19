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

(ns ai.miniforge.agent.stream-watchdog
  "Per-phase event-gap watchdog timer.

   Detects agent stream stalls by tracking the timestamp of the most recent
   event emission and firing a kill signal when the gap exceeds a configurable
   threshold. Runs on a dedicated ScheduledExecutorService daemon thread — never
   blocks event emission on the hot path.

   Layer 0: Config helpers (resolve-gap-threshold)
   Layer 1: Watchdog lifecycle (create-watchdog, reset!, stop!, stalled?)"
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.logging.interface :as log])
  (:import
   (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
   (java.util.concurrent.atomic AtomicLong)))

;; ---------------------------------------------------------------------------
;; Layer 0 — configuration helpers

(def ^:const default-gap-threshold-ms
  "Default stream-gap threshold in milliseconds (90 seconds)."
  90000)

(def ^:const check-interval-seconds
  "How often the scheduled watcher fires, in seconds."
  5)

(defn resolve-gap-threshold
  "Resolve the stream-gap threshold in ms for a given backend.

   Precedence (highest wins):
     1. backend-specific entry in :agent/per-backend-gap-thresholds
     2. global :agent/stream-gap-threshold-ms in config
     3. `default-gap-threshold-ms` (90 000 ms)

   Example config:
     {:agent/stream-gap-threshold-ms 60000
      :agent/per-backend-gap-thresholds {:claude-code 120000}}"
  [config backend]
  (let [base      (get config :agent/stream-gap-threshold-ms default-gap-threshold-ms)
        overrides (get config :agent/per-backend-gap-thresholds {})]
    (get overrides backend base)))

;; ---------------------------------------------------------------------------
;; Layer 1 — watchdog lifecycle

(defn- daemon-thread-factory
  "Return a ThreadFactory that always produces named daemon threads."
  [name-prefix]
  (reify java.util.concurrent.ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable)
        (.setName (str name-prefix "-" (System/nanoTime)))
        (.setDaemon true)))))

(defn- emit-stall-event!
  "Publish a :agent/stream-stalled event to the event-stream.
   Best-effort: log and swallow any emission failure so the watchdog
   thread itself does not crash."
  [event-stream workflow-id phase-id backend logger]
  (try
    (let [envelope (event-stream/create-envelope
                    event-stream
                    :agent/stream-stalled
                    {:phase/id       phase-id
                     :agent/backend  backend
                     :event/workflow-id workflow-id}
                    {})]
      (event-stream/publish! event-stream envelope))
    (catch Exception ex
      (log/warn logger "stream-watchdog: failed to emit :agent/stream-stalled"
                {:phase-id phase-id :backend backend :error (ex-message ex)}))))

(defn- build-check-task
  "Build the Runnable that the scheduler fires every `check-interval-seconds`.

   When the gap between now and the last recorded event timestamp exceeds
   threshold-ms the task:
     a. Calls kill-fn (kills the agent subprocess).
     b. Emits :agent/stream-stalled via event-stream.
     c. Sets the stalled? atom to true.
     d. Shuts down the scheduler (one-shot; idempotent via isShutdown guard)."
  [{:keys [^AtomicLong last-event-ts stalled-atom
           threshold-ms phase-id backend event-stream workflow-id kill-fn
           ^ScheduledExecutorService scheduler logger]}]
  (fn []
    (try
      (when-not (.isShutdown scheduler)
        (let [gap (- (System/currentTimeMillis) (.get last-event-ts))]
          (when (> gap threshold-ms)
            (log/warn logger "stream-watchdog: gap exceeded threshold — killing agent"
                      {:gap-ms gap :threshold-ms threshold-ms
                       :phase-id phase-id :backend backend})
            ;; a. kill the subprocess
            (try (kill-fn)
                 (catch Exception ex
                   (log/warn logger "stream-watchdog: kill-fn threw"
                             {:error (ex-message ex)})))
            ;; b. emit stall event
            (emit-stall-event! event-stream workflow-id phase-id backend logger)
            ;; c. mark stalled
            (reset! stalled-atom true)
            ;; d. shut down scheduler (one-shot)
            (future (.shutdown scheduler)))))
      (catch Exception ex
        (log/error logger "stream-watchdog: unhandled error in check task"
                   {:error (ex-message ex)})))))

(defn create-watchdog
  "Create and start a stream-gap watchdog.

   Options map:
     :threshold-ms  — gap in ms before the kill fires (required)
     :phase-id      — keyword or string identifying the current phase
     :backend       — keyword identifying the agent backend (e.g. :claude-code)
     :event-stream  — event-stream instance to publish the stall event
     :workflow-id   — UUID or string; embedded in the stall event
     :kill-fn       — zero-arity fn that terminates the agent subprocess

   Returns a watchdog state map. Pass it to `reset!`, `stop!`, and `stalled?`.

   The internal scheduler starts immediately on a daemon thread. Events must be
   reported via `reset!` on every emission to keep the watchdog from firing."
  [{:keys [threshold-ms phase-id backend event-stream workflow-id kill-fn]
    :or   {threshold-ms default-gap-threshold-ms}}]
  (let [logger       (log/create-logger {:min-level :info :output :edn})
        last-event-ts (AtomicLong. (System/currentTimeMillis))
        stalled-atom  (atom false)
        scheduler     (Executors/newSingleThreadScheduledExecutor
                       (daemon-thread-factory "stream-watchdog"))
        ctx           {:last-event-ts last-event-ts
                       :stalled-atom  stalled-atom
                       :threshold-ms  threshold-ms
                       :phase-id      phase-id
                       :backend       backend
                       :event-stream  event-stream
                       :workflow-id   workflow-id
                       :kill-fn       (or kill-fn (fn []))
                       :scheduler     scheduler
                       :logger        logger}
        check-task    (build-check-task ctx)]
    (.scheduleAtFixedRate
     scheduler
     check-task
     check-interval-seconds
     check-interval-seconds
     TimeUnit/SECONDS)
    ctx))

(defn reset!
  "Record that an agent event just occurred, resetting the gap timer.

   Call this on every event emitted by the agent (tool-call, chunk, status, etc.).
   Thread-safe via AtomicLong.set — never blocks."
  [watchdog]
  (when-let [^AtomicLong ts (:last-event-ts watchdog)]
    (.set ts (System/currentTimeMillis)))
  watchdog)

(defn stop!
  "Gracefully shut down the watchdog scheduler.

   Call on normal phase completion. Safe to call multiple times; subsequent
   calls are no-ops (scheduler is already shut down).

   Does NOT set :stalled? — a stopped watchdog is distinct from a stalled one."
  [watchdog]
  (when-let [^ScheduledExecutorService sched (:scheduler watchdog)]
    (when-not (.isShutdown sched)
      (.shutdown sched)))
  watchdog)

(defn stalled?
  "Return true if the watchdog fired and killed the agent subprocess.

   Safe to call from any thread; reads an atom."
  [watchdog]
  (boolean (and watchdog @(:stalled-atom watchdog))))

;; ---------------------------------------------------------------------------
;; Rich comment — development examples

(comment
  ;; Minimal watchdog with a 5-second threshold (fires in ~5s without resets)
  (def wd
    (create-watchdog
     {:threshold-ms 5000
      :phase-id     :implement
      :backend      :claude-code
      :event-stream nil
      :workflow-id  "wf-test-001"
      :kill-fn      #(println "KILL SIGNAL")}))

  ;; Simulate an event
  (reset! wd)

  ;; Check status
  (stalled? wd)

  ;; Graceful shutdown
  (stop! wd))
