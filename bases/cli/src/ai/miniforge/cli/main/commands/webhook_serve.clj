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
   verifies each delivery against the webhook secret (the
   MINIFORGE_GITHUB_WEBHOOK_SECRET variable, or the file --secret-file
   names; never a command-line value, which other users can read),
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
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.webhook-intake.interface :as intake]
   [ai.miniforge.workflow.interface :as workflow]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} secret-env-var
  "Environment variable holding the GitHub webhook secret."
  "MINIFORGE_GITHUB_WEBHOOK_SECRET")

(def ^{:stratum 0} default-port
  "Port when --port is absent."
  8788)

(defn ^{:stratum 0} trigger-config
  "The {:triggers [...]} map in `text`, or nil unless every rule names
   the event type it is :on and the :workflow-id it runs."
  [text]
  (let [config (try+ (edn/read-string text) (catch Exception _ nil))
        triggers (:triggers config)]
    (when (and (sequential? triggers)
               (seq triggers)
               (every? #(and (keyword? (:on %)) (some? (get-in % [:run :workflow-id]))) triggers))
      config)))

(defn ^{:stratum 0} block-forever!
  "Park the calling thread until the process exits."
  []
  @(promise))

(defn ^{:stratum 0} add-shutdown-hook!
  "Run `f` when the JVM shuts down."
  [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable f)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} webhook-secret
  "The secret from --secret-file, else from `secret-env-var`."
  [{:keys [secret-file]}]
  (if secret-file
    (str/trim (slurp secret-file))
    (System/getenv secret-env-var)))

(defn ^{:stratum 1} start!
  "Start the intake on a new event stream and the trigger on that stream.
   Returns {:host :port :stop-fn}; throws the intake's anomaly when
   `secret` is blank."
  [config secret {:keys [host port]}]
  (let [stream (es/create-event-stream)
        store (execution/create-artifact-store false)
        listener (intake/start! (cond-> {:secret secret
                                         :stream stream
                                         :port (or port default-port)}
                                  host (assoc :host host)))
        trigger (workflow/create-event-trigger stream config {:artifact-store store
                                                              :event-stream stream})]
    (assoc listener :stop-fn (fn []
                               ((:stop-fn listener))
                               (workflow/stop-trigger! trigger)
                               (execution/close-artifact-store store)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} webhook-serve-cmd
  "Entry point for `mf webhook serve`."
  [{:keys [triggers] :as opts}]
  (let [config (some-> triggers slurp trigger-config)]
    (cond
      (str/blank? triggers)
      (do (display/print-error (messages/t :webhook/triggers-required))
          (shared/exit! 1))

      (nil? config)
      (do (display/print-error (messages/t :webhook/triggers-invalid {:path triggers}))
          (shared/exit! 1))

      :else
      (try+
        (let [{:keys [host port stop-fn]} (start! config (webhook-secret opts) opts)]
          (add-shutdown-hook! (fn []
                                (display/print-info (messages/t :webhook/stopping))
                                (stop-fn)))
          (display/print-info (messages/t :webhook/listening
                                          {:url (str "http://" host ":" port intake/github-path)
                                           :count (count (:triggers config))}))
          (block-forever!))
        (catch [:anomaly/type :invalid-input] refusal
          (display/print-error (messages/t :webhook/start-refused
                                           {:reason (:anomaly/message refusal)
                                            :env secret-env-var}))
          (shared/exit! 1))))))
