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
(ns ai.miniforge.cli.workflow-runner.lifecycle
  "Workflow lifecycle bookkeeping: completion/failure event publication,
   the BD-2b per-workflow manifest (init, terminal mark, heartbeat,
   archive), and the BD-2a event-stream shutdown sequence. Split out of
   `ai.miniforge.cli.workflow-runner` (rule 210: the parent namespace
   measured 10 real layers, max 3; each concern moves to its own
   layer-coherent file)."
  (:require
   [clojure.string :as str]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.interface.manifest :as es-manifest]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failure-message
  "Build a meaningful failure message from a workflow result.

   Error entries are canonically maps carrying `:message`, so read that
   rather than printing the whole entry; entries without one (or that
   aren't maps at all) fall back to their printed form. With no errors
   at all, report the execution status."
  [result]
  (let [status (get result :execution/status :unknown)]
    (if-let [first-error (first (:execution/errors result))]
      (or (:message first-error) (str first-error))
      (str "Workflow ended with status: " (name status)))))

(defn ^{:stratum 0} publish-failure-event!
  "Publish a workflow failure event, swallowing exceptions."
  [event-stream workflow-id error-type message]
  (try
    (es/publish! event-stream
                 (es/workflow-failed event-stream workflow-id
                                     {:message message
                                      :errors [{:type error-type :message message}]}))
    (catch Exception _ nil)))

(defn- ^{:stratum 0} best-effort-shutdown?
  "Honor `MINIFORGE_BEST_EFFORT_SHUTDOWN` as the documented escape hatch
   for local/dev loops that don't care about event durability. Default
   off — normal headless mode treats drain failures as errors."
  []
  (let [v (System/getenv "MINIFORGE_BEST_EFFORT_SHUTDOWN")]
    (boolean (and v (contains? #{"1" "true" "yes" "on"} (str/lower-case v))))))

;; BD-2b sub-3a: per-workflow manifest lifecycle.
;; manifest operations
(def ^{:stratum 0} ^:dynamic *manifest-ops*
  "Manifest operations used by the workflow lifecycle helpers."
  {:init-active       es-manifest/init-active
   :load-manifest     es-manifest/load-manifest
   :mark-terminal     es-manifest/mark-terminal
   :save-manifest!    es-manifest/save-manifest!
   :start-heartbeat!  es-manifest/start-heartbeat!
   :stop-heartbeat!   es-manifest/stop-heartbeat!
   :archive-workflow! es-manifest/archive-workflow!})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish-completion-event [event-stream workflow-id result]
  (let [status (if (phase/succeeded? result) :success :failure)
        duration-ms (get-in result [:execution/metrics :duration-ms])]
    (es/publish! event-stream
                 (if (= status :success)
                   (es/workflow-completed event-stream workflow-id status duration-ms)
                   (es/workflow-failed event-stream workflow-id
                                       {:message (failure-message result)
                                        :errors (or (seq (:execution/errors result))
                                                    [{:type :unknown-failure
                                                      :message (failure-message result)}])})))))

;; lifecycle helpers
;; Peers; none calls another. `run-workflow!` composes them in the parent.
(defn ^{:stratum 1} start-workflow-manifest!
  "Init the manifest at `manifest-dir` and start the heartbeat. Returns
   `{:dir java.io.File :heartbeat ScheduledExecutorService :marked? atom}`
   for the matching `finish-workflow-manifest!`. Returns nil when no
   event stream is configured (dashboard-only run) — no on-disk
   manifest to maintain in that case."
  [workflow-id event-stream]
  (when event-stream
    (let [dir (es/workflow-dir workflow-id)]
      ((:save-manifest! *manifest-ops*) dir ((:init-active *manifest-ops*) workflow-id))
      {:dir       dir
       :heartbeat ((:start-heartbeat! *manifest-ops*) dir)
       :marked?   (atom false)})))

(defn ^{:stratum 1} mark-manifest-terminal!
  "Stamp the manifest with a terminal `status` (`:completed |
   :failed | :cancelled`). Idempotent via the `:marked?` atom — the
   happy path marks `:completed` after drain, the finally block falls
   back to `:cancelled` only if no prior mark fired.

   Only flips `marked?` after a successful load + save. If the
   manifest file is absent (e.g. it was deleted or never written by
   sub-3b's archive flow), this is a no-op that leaves `marked?`
   false so a subsequent attempt (e.g. the finally's :cancelled
   fallback) can still try to write."
  [{:keys [dir marked?]} status]
  (when (and dir (not @marked?))
    (when-let [m ((:load-manifest *manifest-ops*) dir)]
      ((:save-manifest! *manifest-ops*) dir ((:mark-terminal *manifest-ops*) m status))
      (reset! marked? true))))

(defn ^{:stratum 1} finish-workflow-manifest!
  "Stop the heartbeat. Caller is expected to have already called
   `mark-manifest-terminal!` for the happy path; this is the
   shutdown-time cleanup. Swallows exceptions so a manifest IO
   failure during cleanup doesn't mask the workflow result.

   `stop-heartbeat!` can raise `InterruptedException` via
   `awaitTermination`. Swallowing it without restoring the interrupt
   flag breaks cooperative cancellation — outer frames lose the
   signal that they're being asked to shut down. We re-interrupt the
   current thread in that case and still return nil so the cleanup
   stays best-effort."
  [{:keys [heartbeat]}]
  (when heartbeat
    (try
      ((:stop-heartbeat! *manifest-ops*) heartbeat)
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        nil)
      (catch Exception _ nil))))

(defn ^{:stratum 1} archive-workflow-manifest!
  "Run BD-2b sub-3b's atomic archive on `workflow-id`'s `live/`
   directory. Called from the happy path after `mark-manifest-terminal!`
   succeeds — at that point the manifest is at terminal status with
   `archive_status = :live` and ready to transition to `:archived`.

   Best-effort: archive failures (e.g. the manifest disappeared
   between mark and archive, or the rename hit an IO error) are
   logged to stderr but don't propagate. The boot-time
   `archive/recover-all-incomplete!` pass picks up any half-finished
   archives on next start. The finally's `:cancelled` fallback does
   NOT archive — those workflows wait for the scheduled cleanup
   pass (sub-3c) so a crashing pipeline can't get half-archived state
   stuck on disk via the recovery flow."
  [{:keys [dir marked?]} workflow-id]
  (when (and dir @marked?)
    (try
      (let [result ((:archive-workflow! *manifest-ops*) workflow-id)]
        (when (anomaly/anomaly? result)
          (binding [*out* *err*]
            (println (str "WARNING: archive of workflow " workflow-id
                          " failed: " (:anomaly/message result)
                          " (will be recovered by the cleanup pass)")))))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "WARNING: archive of workflow " workflow-id
                        " failed: " (.getMessage e)
                        " (will be recovered by the cleanup pass)")))))))

(defn ^{:stratum 1} event-stream-shutdown!
  "Run the BD-2a shutdown sequence on `es`: quiesce publishers for
   `workflow-id`, then drain sinks. Returns the structured drain result
   for inclusion in the run result map, or `nil` when no event stream
   exists (dashboard-url runs).

   On a non-OK drain in normal mode (best-effort off), throws an
   ex-info carrying the drain result so the caller's catch path renders
   the failure and the CLI exits non-zero. Best-effort mode logs the
   degradation to stderr and returns the result without throwing."
  [es workflow-id {:keys [quiet]}]
  (when es
    (es/quiesce! es {:workflow-id workflow-id :timeout-ms 5000})
    (let [drain-result (es/drain! es {:timeout-ms 5000})]
      (when-not (:ok? drain-result)
        (let [best-effort? (best-effort-shutdown?)
              msg (str "Event-stream drain incomplete on shutdown: "
                       (name (:reason drain-result :unknown))
                       (when-let [pending (:pending-count drain-result)]
                         (str " (pending=" pending ")"))
                       (when-let [failed (:failed-sinks drain-result)]
                         (str " (failed-sinks=" (count failed) ")")))]
          (binding [*out* *err*]
            (println (cond-> msg
                       (not quiet) (->> (display/colorize :yellow))
                       best-effort? (str " [MINIFORGE_BEST_EFFORT_SHUTDOWN=1, continuing]"))))
          (when-not best-effort?
            (response/throw-anomaly! :anomalies/fault
                                     msg
                                     {:reason :event-stream-drain-failed
                                      :drain-result drain-result}))))
      drain-result)))
