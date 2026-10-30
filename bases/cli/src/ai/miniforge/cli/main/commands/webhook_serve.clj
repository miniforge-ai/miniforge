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
(ns ai.miniforge.cli.main.commands.webhook-serve
  "`mf webhook serve`: receive GitHub webhooks and start workflows from
   trigger rules. Runs until SIGINT/SIGTERM.

   Listens on 127.0.0.1 (--host to change) for POST /webhooks/github,
   verifies each delivery against the webhook secret in the file
   --secret-file names (not a command-line value, which other users can
   read, nor an environment variable, which every subprocess of a
   triggered workflow would inherit),
   publishes the mapped events to an event stream, and runs
   workflow/create-event-trigger on that stream with the --triggers file:
   {:triggers [{:on :pr/ci-failed :repo \"owner/name\"
                :run {:workflow-id :some-workflow
                      :input-from-event {:branch :pr/branch}}}]}

   Triggered workflows get the options `mf run` passes the pipeline (an
   artifact store and the event stream), not its per-run manifest or
   event drain. Each matching event starts its workflow at once; there is
   no limit on concurrent runs."
  (:require
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.commands.webhook-triggers :as triggers]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.webhook-intake.interface :as intake]
   [ai.miniforge.workflow.interface :as workflow]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [throw+ try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-port
  "Port when --port is absent."
  8788)

(defn ^{:stratum 0} read-file
  "Contents of the file at `path`, or nil when there is none to read."
  [path]
  (when (and (not (str/blank? path)) (.isFile (io/file path)))
    (slurp path)))

(defn ^{:stratum 0} block-forever!
  "Park the calling thread until the process exits."
  []
  @(promise))

(defn ^{:stratum 0} add-shutdown-hook!
  "Run `f` when the JVM shuts down."
  [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable f)))

(defn ^{:stratum 0} stop-all!
  "Stop the intake first, so no delivery is accepted with no trigger
   listening, then the trigger, then close the artifact store."
  [listener trigger store]
  ((:stop-fn listener))
  (workflow/stop-trigger! trigger)
  (execution/close-artifact-store store))

(defn ^{:stratum 0} announce-stop!
  "Print that the intake is stopping and run `stop-fn`."
  [stop-fn]
  (display/print-info (messages/t :webhook/stopping))
  (stop-fn))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} start!
  "Subscribe the trigger to a new event stream, then start the intake
   publishing to it, so no delivery is accepted before a trigger
   listens. Returns {:host :port :stop-fn}; throws the intake's anomaly
   when `secret` is blank."
  [config secret {:keys [host port] :or {port default-port}}]
  (let [stream (es/create-event-stream {:retention es/bounded-retention})
        store (execution/create-artifact-store false)
        trigger (workflow/create-event-trigger stream config {:artifact-store store
                                                              :event-stream stream})
        listener (try+
                   (intake/start! (cond-> {:secret secret :stream stream :port port}
                                    host (assoc :host host)))
                   (catch Object _
                     (workflow/stop-trigger! trigger)
                     (execution/close-artifact-store store)
                     (throw+)))]
    (assoc listener :stop-fn (partial stop-all! listener trigger store))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} webhook-serve-cmd
  "Entry point for `mf webhook serve`."
  [{:keys [triggers secret-file] :as opts}]
  (let [config (some-> (read-file triggers) triggers/trigger-config)]
    (cond
      (str/blank? triggers)
      (do (display/print-error (messages/t :webhook/triggers-required))
          (shared/exit! 1))

      (nil? config)
      (do (display/print-error (messages/t :webhook/triggers-invalid {:path triggers}))
          (shared/exit! 1))

      :else
      (try+
        (let [{:keys [host port stop-fn]} (start! config (some-> (read-file secret-file) str/trim) opts)]
          (add-shutdown-hook! (partial announce-stop! stop-fn))
          (display/print-info (messages/t :webhook/listening
                                          {:url (str "http://" host ":" port intake/github-path)
                                           :count (count (:triggers config))}))
          (block-forever!))
        (catch [:anomaly/type :invalid-input] refusal
          (display/print-error (messages/t :webhook/start-refused
                                           {:reason (:anomaly/message refusal)}))
          (shared/exit! 1))))))
