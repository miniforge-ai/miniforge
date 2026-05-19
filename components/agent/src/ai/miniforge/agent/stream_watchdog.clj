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
   Layer 1: Watchdog lifecycle (create-watchdog, ping!, stop!, stalled?)"
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.logging.interface :as log])
  (:import
   (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
   (java.util.concurrent.atomic AtomicLong)))

;; ---------------------------------------------------------------------------
;; Layer 0 — module-level logger and configuration helpers

(defonce ^:private module-logger
  (log/create-logger {:min-level :info :output :edn}))

(def ^:const default-gap-threshold-ms
  "Default stream-gap threshold in milliseconds (90 seconds)."
  90000)

(def ^:const default-check-interval-ms
  "Default watchdog check interval in milliseconds (5 seconds)."
  5000)

(defn resolve-gap-threshold
  "Resolve the stream-gap threshold in ms for a given backend.

   Precedence (highest wins):
     1. Backend-specific entry in :agent/per-backend-gap-thresholds
     2. Global :agent/stream-gap-threshold-ms in config
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

;; ---------------------------------------------------------------------------
;; Layer 0.5 — session capture helpers

(defn- extract-session-id
  "Extract session ID from a handshake event map.

   Handles two common backend shapes:
   - Claude Code: top-level `:session_id` or `\"session_id\"` key
   - Codex: nested `[:session :id]` or `[\"session\" \"id\"]`

   Returns nil when no recognizable session key is present."
  [event-map]
  (or (get event-map :session_id)
      (get event-map "session_id")
      (get-in event-map [:session :id])
      (get-in event-map ["session" "id"])))

(defn- emit-session-captured!
  "Publish :agent/session-captured via event-stream.

   nil event-stream is a legal no-op (see emit-stall-event! for the same
   pattern). Any emission error is caught and logged — the capture itself
   has already been persisted in the atom, so a delivery failure must not
   lose the session ID."
  [{:keys [event-stream workflow-id phase-id backend logger]} session-id]
  (if-not event-stream
    (log/info logger "stream-watchdog: no event-stream; session-captured suppressed"
              {:session-id session-id :phase-id phase-id :backend backend})
    (try
      (let [envelope (event-stream/agent-session-captured
                      event-stream workflow-id phase-id session-id backend)]
        (event-stream/publish! event-stream envelope))
      (catch Exception ex
        (log/warn logger "stream-watchdog: failed to emit :agent/session-captured"
                  {:session-id session-id :phase-id phase-id
                   :backend backend :error (ex-message ex)})))))

(defn- emit-stall-event!
  "Publish a :agent/stream-stalled event to event-stream.

   nil event-stream is treated as a legal no-op (useful in tests and in early
   pipeline stages that have not yet wired an event-stream). Any emission error
   is caught and logged so the watchdog thread cannot crash."
  [event-stream workflow-id phase-id backend logger]
  (if-not event-stream
    (log/warn logger "stream-watchdog: no event-stream configured; stall event suppressed"
              {:phase-id phase-id :backend backend})
    (try
      (let [envelope (event-stream/create-envelope
                      event-stream
                      :agent/stream-stalled
                      {:phase/id          phase-id
                       :agent/backend     backend
                       :event/workflow-id workflow-id}
                      {})]
        (event-stream/publish! event-stream envelope))
      (catch Exception ex
        (log/warn logger "stream-watchdog: failed to emit :agent/stream-stalled"
                  {:phase-id phase-id :backend backend :error (ex-message ex)})))))

(defn- build-check-task
  "Build the Runnable that the scheduler fires every check interval.

   When (now − last-event-timestamp) exceeds threshold-ms the task:
     a. Calls kill-fn (kills the agent subprocess).
     b. Emits :agent/stream-stalled via event-stream.
     c. Sets the stalled? atom to true.
     d. Shuts down the scheduler (one-shot; non-blocking from own thread)."
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
            ;; b. emit stall event (nil-safe)
            (emit-stall-event! event-stream workflow-id phase-id backend logger)
            ;; c. mark stalled
            (clojure.core/reset! stalled-atom true)
            ;; d. shut down scheduler — non-blocking, safe from own thread
            (.shutdown scheduler))))
      (catch Exception ex
        (log/error logger "stream-watchdog: unhandled error in check task"
                   {:error (ex-message ex)})))))

(defn create-watchdog
  "Create and start a stream-gap watchdog.

   Options map:
     :threshold-ms      — gap in ms before the kill fires (default 90 000)
     :check-interval-ms — how often to check for a stall (default 5 000)
     :phase-id          — keyword or string identifying the current phase
     :backend           — keyword identifying the agent backend (e.g. :claude-code)
     :event-stream      — event-stream instance to publish stall/session events;
                          nil is accepted and suppresses event emission
     :workflow-id       — UUID or string; embedded in emitted events
     :kill-fn           — zero-arity fn that terminates the agent subprocess
     :logger            — optional logger; defaults to the module-level logger

   Returns a watchdog state map. Pass it to `ping!`, `stop!`, `stalled?`,
   `capture-session-id!`, and `get-session-id`.

   The internal scheduler starts immediately on a daemon thread. Report every
   agent event via `ping!` to keep the watchdog from firing."
  [{:keys [threshold-ms check-interval-ms phase-id backend
           event-stream workflow-id kill-fn logger]
    :or   {threshold-ms      default-gap-threshold-ms
           check-interval-ms default-check-interval-ms
           logger            module-logger}}]
  (let [last-event-ts   (AtomicLong. (System/currentTimeMillis))
        stalled-atom    (atom false)
        session-id-atom (atom nil)
        scheduler       (Executors/newSingleThreadScheduledExecutor
                         (daemon-thread-factory "stream-watchdog"))
        ctx             {:last-event-ts     last-event-ts
                         :stalled-atom      stalled-atom
                         :session-id-atom   session-id-atom
                         :threshold-ms      threshold-ms
                         :check-interval-ms check-interval-ms
                         :phase-id          phase-id
                         :backend           backend
                         :event-stream      event-stream
                         :workflow-id       workflow-id
                         :kill-fn           (or kill-fn (fn []))
                         :scheduler         scheduler
                         :logger            logger}
        check-task    (build-check-task ctx)]
    (.scheduleAtFixedRate
     scheduler
     check-task
     check-interval-ms
     check-interval-ms
     TimeUnit/MILLISECONDS)
    ctx))

(defn ping!
  "Record that an agent event just occurred, resetting the gap timer.

   Call this on every event emitted by the agent (tool-call, chunk, status, etc.).
   Thread-safe via AtomicLong.set — never blocks.

   Named `ping!` (not `reset!`) to avoid shadowing `clojure.core/reset!`."
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
;; Layer 1.5 — session ID capture

(defn capture-session-id!
  "Parse and persist the session ID from the initial agent handshake event.

   MUST be called synchronously before the first tool call so that
   resume-on-kill has a valid session ID available.

   Accepts the first parsed JSON event from the agent subprocess as a
   Clojure map. Supports two common backend handshake shapes:
   - Claude Code: top-level `:session_id` key
   - Codex:       nested `[:session :id]` path

   When a session ID is found:
     1. Stores it in the `:session-id-atom` on the watchdog state (atomic).
     2. Emits :agent/session-captured via event-stream (nil stream is a no-op).

   When no recognizable session key is present, logs a warning and returns
   the watchdog unchanged; this is not a fatal condition.

   Thread-safe — atom reset is atomic; safe to call from the stream-parsing
   thread concurrent with ping! on the watchdog scheduler thread."
  [watchdog event-map]
  (if-let [sid (extract-session-id event-map)]
    (do
      (reset! (:session-id-atom watchdog) sid)
      (emit-session-captured! watchdog sid)
      watchdog)
    (do
      (log/warn (:logger watchdog)
                "stream-watchdog: no session ID found in handshake event"
                {:event-keys (keys event-map)
                 :phase-id   (:phase-id watchdog)
                 :backend    (:backend watchdog)})
      watchdog)))

(defn get-session-id
  "Return the captured session ID string, or nil if not yet captured.

   Resume-on-kill reads this to pass the correct --resume flag to the backend.
   Safe to call from any thread."
  [watchdog]
  (when watchdog
    @(:session-id-atom watchdog)))

;; ---------------------------------------------------------------------------
;; Rich comment — development examples

(comment
  ;; Minimal watchdog with fast settings for REPL experimentation
  (def wd
    (create-watchdog
     {:threshold-ms      1000
      :check-interval-ms 100
      :phase-id          :implement
      :backend           :claude-code
      :event-stream      nil
      :workflow-id       "wf-test-001"
      :kill-fn           #(println "KILL SIGNAL")}))

  ;; Simulate an event ping
  (ping! wd)

  ;; Check status
  (stalled? wd)

  ;; Graceful shutdown on normal completion
  (stop! wd))
