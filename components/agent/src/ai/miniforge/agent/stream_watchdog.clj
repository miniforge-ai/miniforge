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
  "Per-phase event-gap watchdog timer with session ID capture.

   Detects agent stream stalls by tracking the timestamp of the most recent
   event emission and firing a kill signal when the gap exceeds a configurable
   threshold. Runs on a dedicated ScheduledExecutorService daemon thread — never
   blocks event emission on the hot path.

   Also captures and persists the backend session ID from the initial agent
   handshake so that resume-on-kill can restart the agent at the correct
   session checkpoint.

   Layer 0: Config helpers (resolve-gap-threshold, extract-session-id,
            emit-session-captured!, emit-stall-event!)
   Layer 1: Watchdog lifecycle (create-watchdog, ping!, stop!, stalled?,
            capture-session-id!, get-session-id)"
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.logging.interface :as log])
  (:import
   (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
   (java.util.concurrent.atomic AtomicLong)))

;; ---------------------------------------------------------------------------
;; Layer 0 — module-level logger, configuration helpers, and session helpers

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

(defn- extract-session-id
  "Extract session ID from a handshake event map.

   Handles two common backend shapes:
   - Claude Code: top-level `:session_id` or `\"session_id\"` key
   - Codex:       nested `[:session :id]` or `[\"session\" \"id\"]`

   Returns nil when no recognizable session key is present."
  [event-map]
  (or (get event-map :session_id)
      (get event-map "session_id")
      (get-in event-map [:session :id])
      (get-in event-map ["session" "id"])))

(defn- emit-session-captured!
  "Publish :agent/session-captured via event-stream.

   nil event-stream is a legal no-op, consistent with emit-stall-event!.
   Any emission error is caught and logged — the capture has already been
   persisted in the atom so a delivery failure must not lose the session ID."
  [{:keys [event-stream workflow-id phase-id backend logger]} session-id]
  (if-not event-stream
    (log/debug logger :stream-watchdog :session-captured/suppressed
               {:reason :no-event-stream
                :session/id session-id
                :workflow/phase phase-id
                :agent/backend backend})
    (try
      (let [envelope (event-stream/agent-session-captured
                      event-stream workflow-id phase-id session-id backend)]
        (event-stream/publish! event-stream envelope))
      (catch Exception ex
        (log/warn logger :stream-watchdog :session-captured/emit-failed
                  {:session/id session-id
                   :workflow/phase phase-id
                   :agent/backend backend
                   :error (ex-message ex)})))))

(defn- emit-stall-event!
  "Publish an :agent/stream-stalled event to event-stream.

   Uses the canonical agent-stream-stalled constructor so that :workflow/id
   is a proper UUID/string and :stream/gap-duration-ms is populated.

   nil event-stream is treated as a legal no-op (useful in tests and in early
   pipeline stages that have not yet wired an event-stream). Any emission error
   is caught and logged so the watchdog thread cannot crash."
  [event-stream workflow-id phase-id backend gap-ms logger]
  (if-not event-stream
    (log/debug logger :stream-watchdog :stall-event/suppressed
               {:reason :no-event-stream
                :workflow/phase phase-id
                :agent/backend backend})
    (try
      (let [envelope (event-stream/agent-stream-stalled
                      event-stream workflow-id phase-id gap-ms backend)]
        (event-stream/publish! event-stream envelope))
      (catch Exception ex
        (log/warn logger :stream-watchdog :stall-event/emit-failed
                  {:workflow/phase phase-id
                   :agent/backend backend
                   :error (ex-message ex)})))))

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

(defn- build-check-task
  "Build the Runnable that the scheduler fires every check interval.

   When (now − last-event-timestamp) exceeds threshold-ms the task:
     a. Calls kill-fn (kills the agent subprocess).
     b. Emits :agent/stream-stalled via event-stream with the measured gap.
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
            (log/warn logger :stream-watchdog :stream/gap-exceeded
                      {:stream/gap-duration-ms gap
                       :stream/gap-threshold-ms threshold-ms
                       :workflow/phase phase-id
                       :agent/backend backend})
            ;; a. kill the subprocess
            (try (kill-fn)
                 (catch Exception ex
                   (log/warn logger :stream-watchdog :stream/kill-fn-failed
                             {:workflow/phase phase-id
                              :agent/backend backend
                              :error (ex-message ex)})))
            ;; b. emit stall event with measured gap (nil-safe)
            (emit-stall-event! event-stream workflow-id phase-id backend gap logger)
            ;; c. mark stalled
            (clojure.core/reset! stalled-atom true)
            ;; d. shut down scheduler — non-blocking, safe from own thread
            (.shutdown scheduler))))
      (catch Exception ex
        (log/error logger :stream-watchdog :check-task/unhandled-error
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
        check-task      (build-check-task ctx)]
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

(defn capture-session-id!
  "Parse and persist the session ID from the initial agent handshake event.

   MUST be called synchronously before the first tool call so that
   resume-on-kill has a valid session ID available.

   Idempotent — once a session ID has been captured further calls are
   no-ops. This prevents a second :agent/session-captured event if the
   consumer mistakenly passes multiple early events.

   Accepts the first parsed JSON event from the agent subprocess as a
   Clojure map. Supports two common backend handshake shapes:
   - Claude Code: top-level `:session_id` key
   - Codex:       nested `[:session :id]` path

   When a session ID is found for the first time:
     1. Stores it in the `:session-id-atom` on the watchdog state (atomic).
     2. Emits :agent/session-captured via event-stream (nil stream is a no-op).

   When the session ID has already been captured, returns the watchdog unchanged.
   When no recognizable session key is present, logs a warning and returns
   the watchdog unchanged; this is not a fatal condition.

   nil watchdog (or one missing :session-id-atom) is a legal no-op — useful
   for early-pipeline call sites that have not constructed a watchdog yet.

   Thread-safe — uses compare-and-set! so concurrent callers cannot both
   observe a nil session-id and both emit :agent/session-captured."
  [watchdog event-map]
  (if-let [sid-atom (and watchdog (:session-id-atom watchdog))]
    (if-let [sid (extract-session-id event-map)]
      (if (compare-and-set! sid-atom nil sid)
        (do
          (emit-session-captured! watchdog sid)
          watchdog)
        watchdog)
      (do
        (log/warn (:logger watchdog) :stream-watchdog :session/handshake-missing-id
                  {:event-keys (keys event-map)
                   :workflow/phase (:phase-id watchdog)
                   :agent/backend (:backend watchdog)})
        watchdog))
    watchdog))

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

  ;; Capture session ID from the first handshake event (Claude Code shape)
  (capture-session-id! wd {:session_id "cc-abc123" :type "system" :subtype "init"})

  ;; Read back the captured ID
  (get-session-id wd)

  ;; Check stall status
  (stalled? wd)

  ;; Graceful shutdown on normal completion
  (stop! wd))
