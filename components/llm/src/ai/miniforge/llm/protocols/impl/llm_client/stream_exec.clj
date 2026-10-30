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
(ns ai.miniforge.llm.protocols.impl.llm-client.stream-exec
  "Running the agent CLI as a streaming subprocess: `stream-exec-fn`, the
   stream loop, the stream-timing settings, the keepalive interval, the
   default progress monitor, and the result maps."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.llm.network-monitor :as nnm]
   [ai.miniforge.llm.progress-monitor :as pm]
   [ai.miniforge.llm.protocols.impl.llm-client.backend-registry :as backend-registry]
   [ai.miniforge.llm.protocols.impl.llm-client.settings :as settings]
   [ai.miniforge.llm.protocols.impl.llm-client.stream-reader :as stream-reader]
   [ai.miniforge.llm.protocols.impl.llm-client.subprocess :as subprocess]
   [slingshot.slingshot :refer [throw+ try+]])
  (:import
   [java.util.concurrent LinkedBlockingQueue]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} format-timeout-error [{:keys [message type elapsed-ms]}]
  (format "Adaptive timeout: %s (type: %s, elapsed: %dms)"
          message (name type) elapsed-ms))

(defn ^{:stratum 0} success-result [out-lines process-result]
  {:out (str/join "\n" out-lines)
   :err (:err process-result)
   :exit (:exit process-result)})

(defn ^{:stratum 0} stream-anomaly-result [out-lines stream-anomaly]
  {:out (str/join "\n" out-lines)
   :err (:anomaly/message stream-anomaly)
   :exit -1
   :anomaly stream-anomaly})

(def ^{:stratum 0} ^:private keepalive-min-interval-ms
  "Floor on the keepalive interval so the worker doesn't burn cycles
   pinging the monitor in a tight loop on production-sized thresholds.
   1s is plenty fine-grained for a 180+ second stagnation window."
  1000)

(defn- ^{:stratum 0} network-drop-timeout
  "Shape the network-monitor's `:on-drop` diagnostic into the same
   `{:type :message :elapsed-ms ...}` envelope `pm/check-timeout`
   returns, so the existing `timeout-result` path handles it
   identically. The PR-C auto-resumer keys on `:type :network-drop` to
   know it should resume from the last persisted checkpoint."
  [{:keys [backend-key consecutive-failures probe-interval-ms]
    :as   diag}]
  {:type           :network-drop
   :backend-key    backend-key
   :elapsed-ms     (* consecutive-failures probe-interval-ms)
   :message        (format "Network drop: %d consecutive probes failed for %s"
                           consecutive-failures (name backend-key))
   :stats          (select-keys diag
                                [:consecutive-failures :failure-threshold
                                 :probe-interval-ms])})

(defn- ^{:stratum 0} stop-stream-process!
  "Wait up to `join-timeout` ms for the stream subprocess to exit and
   collect its stderr. `kill?` (the stream ended on a timeout or an
   anomaly) destroys the process tree first. A process still running
   when the wait runs out (it closed stdout but did not exit) is
   destroyed too, so the agent CLI never outlives the call."
  [{:keys [^Process process stdin-thread stderr-thread stderr]} kill? join-timeout]
  (when kill?
    (subprocess/destroy-process-tree! process))
  (let [result (subprocess/wait-for-stream-process process join-timeout)]
    (when (and (not kill?) (= -1 (:exit result)))
      (subprocess/destroy-process-tree! process))
    (when stdin-thread
      (.join ^Thread stdin-thread subprocess/stream-reader-join-timeout-ms))
    (when stderr-thread
      (.join ^Thread stderr-thread subprocess/stream-reader-join-timeout-ms))
    (assoc result :err @stderr)))

;; ---------------------------------------------------------------------------
;; Stream-timing constants
;;
;; Names spell out intent so the surrounding code reads as the why,
;; not the what. Tuning history lives in inline doc-comments next to
;; each constant.
(defn ^{:stratum 0} default-stagnation-threshold-ms
  []
  (settings/client-default [:stream :default-stagnation-threshold-ms]))

(defn ^{:stratum 0} default-max-total-ms
  []
  (settings/client-default [:stream :default-max-total-ms]))

(defn- ^{:stratum 0} default-min-activity-interval-ms
  []
  (settings/client-default [:stream :default-min-activity-interval-ms]))

(defn- ^{:stratum 0} stream-line-timeout-ms
  []
  (settings/client-default [:stream :line-timeout-ms]))

(defn- ^{:stratum 0} process-join-timeout-ms
  []
  (settings/client-default [:stream :process-join-timeout-ms]))

(defn ^{:stratum 0} post-kill-join-timeout-ms
  []
  (settings/client-default [:stream :post-kill-join-timeout-ms]))

(defn- ^{:stratum 0} start-stream-process!
  [cmd {:keys [workdir stdin]}]
  (let [builder (ProcessBuilder. ^java.util.List cmd)]
    (subprocess/apply-process-env! builder (subprocess/agent-env))
    (when workdir
      (.directory builder (io/file workdir)))
    (let [process (.start builder)
          stderr (atom "")
          stdin-thread (subprocess/write-process-stdin! process stdin)
          stderr-thread (subprocess/read-process-stderr! process stderr)]
      {:process process
       :stderr stderr
       :stdin-thread stdin-thread
       :stderr-thread stderr-thread})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} timeout-result [out-lines timeout-reason]
  {:out (str/join "\n" out-lines)
   :err (format-timeout-error timeout-reason)
   :exit -1
   :timeout timeout-reason})

(defn ^{:stratum 1} default-progress-monitor []
  (pm/create-progress-monitor
   {:stagnation-threshold-ms  (default-stagnation-threshold-ms)
    :max-total-ms             (default-max-total-ms)
    :min-activity-interval-ms (default-min-activity-interval-ms)}))

(defn- ^{:stratum 1} keepalive-interval-ms
  "Refresh interval for the stream-side keepalive thread.

   Tied to the configured stagnation threshold so the keepalive fires
   well within the window — at one-third of the threshold we get
   three refreshes per stagnation-window, so a brief reader stall
   never races the timer.

   Two clamps:
   - lower: `keepalive-min-interval-ms` to avoid a hot-loop on
     production stagnation values.
   - upper: strictly less than the stagnation threshold so the
     keepalive always fires before stagnation can. The min wins
     when the configured threshold is small (e.g. tests using
     80ms) so the lower-bound floor doesn't push the interval
     past the threshold."
  [monitor]
  (let [stagnation (get @monitor :stagnation-threshold-ms
                        (default-stagnation-threshold-ms))
        target     (long (/ stagnation 3))
        ceiling    (max 1 (dec stagnation))]
    (min ceiling (max keepalive-min-interval-ms target))))

(defn ^{:stratum 1} process-stream-lines [out-reader monitor on-line]
  (let [out-lines (atom [])
        timeout-reason (atom nil)
        stream-anomaly (atom nil)
        line-timeout-ms (stream-line-timeout-ms)
        last-line-at (atom (System/currentTimeMillis))
        dump-writer (stream-reader/open-stream-dump-writer)
        line-queue (LinkedBlockingQueue.)
        reader-thread (stream-reader/start-stream-reader! out-reader line-queue)]
    (try+
      (loop []
        (if-let [t (pm/check-timeout monitor)]
          (reset! timeout-reason t)
          (let [{:keys [done? timeout anomaly]}
                (stream-reader/process-stream-signal (stream-reader/stream-poll-signal line-queue)
                                       out-lines
                                       dump-writer
                                       on-line
                                       last-line-at
                                       line-timeout-ms)]
            (cond
              anomaly (reset! stream-anomaly anomaly)
              done? nil
              timeout (reset! timeout-reason timeout)
              :else (recur)))))
      (finally
        (stream-reader/stop-stream-reader! reader-thread out-reader)
        (when dump-writer (.close dump-writer))))
    {:lines @out-lines
     :timeout @timeout-reason
     :anomaly @stream-anomaly}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} stream-exec-fn
  "Run `cmd` as a streaming subprocess, dispatching each output line to
   `on-line`.

   Options:
   - `:progress-monitor`  — caller-supplied progress monitor; defaults
                            to a fresh `default-progress-monitor`.
   - `:workdir`, `:stdin` — passed through to `start-stream-process!`.
   - `:backend-key`       — when set, starts the network-drop monitor
                            against the corresponding provider endpoint
                            (PR-B). Disabled when omitted so legacy
                            callers / synthetic-stream tests are
                            unaffected.
   - `:network-monitor-opts` — overrides for `start-network-monitor!`
                               (probe-interval-ms, failure-threshold,
                               probe-fn). Tests inject `:probe-fn` here
                               to drive the monitor deterministically
                               without real network I/O.

   Every exit other than a clean one (timeout, stream anomaly, network
   drop, an exception, or this thread being interrupted) destroys the
   subprocess and everything it spawned. On interrupt the interrupt flag
   is restored before the InterruptedException is rethrown."
  ([cmd on-line]
   (stream-exec-fn cmd on-line {}))
  ([cmd on-line {:keys [progress-monitor workdir stdin backend-key
                        network-monitor-opts]}]
   (let [monitor (or progress-monitor (default-progress-monitor))
         stream-process (start-stream-process! cmd {:workdir workdir
                                                    :stdin stdin})
         process (:process stream-process)]
     ;; The implement watchdog stops a stalled agent by interrupting this
     ;; thread. Unless the agent CLI dies here it keeps running, MCP
     ;; servers and Bash children included, and keeps editing the
     ;; worktree while the phase retries with a second agent.
     (try+
       (let [out-reader (java.io.BufferedReader.
                         (java.io.InputStreamReader. (.getInputStream process)))
             ;; Decouple stagnation from the CLI's emission cadence.
             ;; claude in --input-format text mode does not emit
             ;; rate_limit_event during the model's think phase, so the
             ;; stream-heartbeat-based stagnation guard has no signal
             ;; during legitimate thinking on heavy prompts (2026-05-07
             ;; stage-3 dogfood: stagnation timeout at 180s with 6
             ;; init-time chunks then nothing for the model's full
             ;; think). Keepalive refreshes the monitor while the JVM-side
             ;; reader is alive; max-total-ms remains the wedge backstop.
             stop-keepalive! (pm/start-keepalive! monitor
                                                  (keepalive-interval-ms monitor))
             ;; Network-drop monitor — independent of the keepalive. PR-B
             ;; of the network-resilience stack. Probes the provider every
             ;; `default-probe-interval-ms` (10s) and fires once after
             ;; `default-failure-threshold` (3) consecutive failures.
             ;; on-drop: stamps the network-drop reason into the shared
             ;; atom and destroys the subprocess tree so the reader
             ;; loop exits immediately (the agent is hung on socket I/O
             ;; that will never recover without process restart).
             ;; Disabled when no :backend-key is provided so legacy
             ;; callers (tests with synthetic streams, the `:echo` test
             ;; backend) keep working unchanged.
             network-drop-reason (atom nil)
             stop-network-monitor!
             (when backend-key
               ;; Caller opts merged FIRST so the integration-owned
               ;; :backend-key, :probe-url, and :on-drop always win — a
               ;; test fixture that mistakenly carries those keys can't
               ;; clobber the monitor's correctness contract. Test-only
               ;; knobs (:probe-interval-ms, :failure-threshold, :probe-fn)
               ;; still flow through.
               (nnm/start-network-monitor!
                 (merge network-monitor-opts
                        {:backend-key backend-key
                         :probe-url   (backend-registry/probe-endpoint-for backend-key)
                         :on-drop     (fn [diag]
                                        (compare-and-set! network-drop-reason nil
                                                          (network-drop-timeout diag))
                                        ;; Kill the subprocess tree so the
                                        ;; consumer's reader sees EOF (no
                                        ;; child left holding stdout) and
                                        ;; the loop exits. stop-stream-process!
                                        ;; kills again; that is a no-op.
                                        (subprocess/destroy-process-tree! process))})))
             {:keys [lines timeout anomaly]}
             (try
               (process-stream-lines out-reader monitor on-line)
               (finally
                 (stop-keepalive!)
                 (when stop-network-monitor! (stop-network-monitor!))))
             ;; A network-drop signal (if any) supersedes whatever
             ;; pm/check-timeout returned — the subprocess was killed by
             ;; the network monitor, so `process-stream-lines` likely
             ;; observed a stream-idle as a knock-on effect. Surface the
             ;; root cause for the workflow / PR-C auto-resumer.
             effective-timeout (or @network-drop-reason timeout)
             ;; Stream ended with a timeout reason (stream-idle,
             ;; stagnation, total-max, or network-drop) — the subprocess
             ;; is hung or silent. Kill it so `deref` returns immediately
             ;; instead of waiting 10 min for a process that will not exit.
             terminal-failure (or effective-timeout anomaly)
             join-timeout (if terminal-failure
                            (post-kill-join-timeout-ms)
                            (process-join-timeout-ms))
             result (stop-stream-process! stream-process
                                          (boolean terminal-failure)
                                          join-timeout)]
         (cond
           effective-timeout
           (timeout-result lines effective-timeout)

           anomaly
           (stream-anomaly-result lines anomaly)

           :else
           (success-result lines result)))
       (catch InterruptedException _
         ;; Kill first, then restore the flag the exception cleared, so
         ;; the caller still sees the interrupt.
         (subprocess/destroy-process-tree! process)
         (.interrupt (Thread/currentThread))
         (throw+))
       ;; `Object` is slingshot's catch-all: it also matches ex-info and
       ;; throw+ payloads, which a `Throwable` selector would miss.
       (catch Object _
         (subprocess/destroy-process-tree! process)
         (throw+))))))
