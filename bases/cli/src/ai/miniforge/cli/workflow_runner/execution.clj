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
(ns ai.miniforge.cli.workflow-runner.execution
  "Pipeline execution wrappers: artifact-store lifecycle, the plain
   pipeline invocation, and the event-publishing execution path used by
   spec-driven runs (completion/failure events, sandbox cleanup).
   Split out of `ai.miniforge.cli.workflow-runner` (rule 210: the
   parent namespace measured 10 real layers, max 3; each concern moves
   to its own layer-coherent file)."
  (:require
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.lifecycle :as lifecycle]
   [slingshot.slingshot :refer [try+ throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create-artifact-store [quiet]
  (try
    (artifact/create-transit-store)
    (catch Exception _e
      (when-not quiet
        (println (display/colorize :yellow (messages/t :workflow-runner/artifact-store-warning))))
      nil)))

(defn ^{:stratum 0} close-artifact-store [artifact-store]
  (when artifact-store
    (try
      (artifact/close-store artifact-store)
      (catch Exception _))))

(defn- ^{:stratum 0} sandbox-failure-result
  "Workflow result for a sandbox that never came up.

   Speaks the same `:execution/*` vocabulary as the pipeline's own
   results, so `phase/succeeded?`, `display/print-result` and
   `lifecycle/publish-completion-event` classify and render it like any
   other workflow failure instead of seeing a statusless map."
  [sandbox-error]
  (let [error (:error sandbox-error)]
    {:execution/status :failed
     :execution/errors [{:type :sandbox-setup-failed
                         :message (or (:message error) (str error))}]}))

(defn ^{:stratum 0} execute-workflow-pipeline [run-pipeline workflow input callbacks artifact-store event-stream]
  (-> callbacks
      (cond-> artifact-store (assoc :artifact-store artifact-store))
      (cond-> event-stream (assoc :event-stream event-stream))
      (->> (run-pipeline workflow input))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute-with-events [{:keys [run-pipeline workflow workflow-input context artifact-store
                                                 event-stream workflow-id sandbox-cleanup opts]}]
  (let [completed? (atom false)]
    (try+
      (if-let [sandbox-error (:sandbox-error context)]
        (let [result (sandbox-failure-result sandbox-error)]
          (lifecycle/publish-completion-event event-stream workflow-id result)
          (reset! completed? true)
          (display/print-result result opts)
          result)
        (let [result (execute-workflow-pipeline run-pipeline workflow workflow-input context artifact-store event-stream)]
          (lifecycle/publish-completion-event event-stream workflow-id result)
          (reset! completed? true)
          (close-artifact-store artifact-store)
          (display/print-result result opts)
          result))
      (catch Object _
        (let [e (:throwable &throw-context)]
          (when-not @completed?
            (lifecycle/publish-failure-event! event-stream workflow-id :interrupted
                                              (messages/t :workflow-runner/stopped {:error (ex-message e)}))
            (reset! completed? true))
          (throw+)))
      (finally
        (when-not @completed?
          (lifecycle/publish-failure-event! event-stream workflow-id :cancelled
                                            (messages/t :workflow-runner/cancelled)))
        (when sandbox-cleanup
          (sandbox-cleanup)
          (when-not (:quiet opts)
            (println (display/colorize :yellow (messages/t :workflow-runner/sandbox-released)))))))))
