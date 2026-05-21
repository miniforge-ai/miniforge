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

(ns ai.miniforge.cli.main.commands.events
  "CLI `events show <workflow-id>` subcommand.

   Reads the local file-sink event log for a workflow and renders a
   human-readable timeline.  Works entirely offline — no network calls.

   Key behaviours:
   - Resolves events directory via app-config/events-dir (same root as main.clj).
   - Probes archived → live → legacy directory layouts via the event-stream
     interface (read-workflow-events-by-id).
   - Renders via event-stream interface (render-timeline).
   - Optional --raw flag dumps parsed events as EDN (debug aid).
   - Optional --gap-threshold <seconds> (default 60) tunes gap detection.
   - Exits 0 on success, 1 when the workflow-id is unknown or the
     events directory is absent."
  (:require
   [babashka.fs :as fs]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:private default-gap-threshold-secs
  "Gap detection threshold in seconds used when --gap-threshold is absent."
  60)

;------------------------------------------------------------------------------ Layer 1
;; Pure helpers

(defn- gap-threshold-ms
  "Convert a seconds threshold to milliseconds, falling back to the default."
  [threshold-secs]
  (* (or threshold-secs default-gap-threshold-secs) 1000))

;------------------------------------------------------------------------------ Layer 2
;; Core (testable) implementation

(defn events-show
  "Read and render the event log for `workflow-id` under `base-dir`.

   Arguments:
   - `base-dir`    — base events directory (string or java.io.File);
                     matches what app-config/events-dir returns.
   - `workflow-id` — workflow UUID string.
   - `opts`        — map of {:gap-threshold <secs> :raw <boolean>}.

   Returns one of:
     {:status :ok    :output <string>}
     {:status :error :message <string> :exit-code 1}

  Pure in structure — all IO is isolated to es/read-workflow-events-by-id
   which is easy to stub in tests."
  [base-dir workflow-id {:keys [gap-threshold raw]}]
  (try
    (cond
      (str/blank? workflow-id)
      {:status :error
       :message (messages/t :events/workflow-id-required)
       :exit-code 1}

      (not (fs/exists? base-dir))
      {:status    :error
       :message   (messages/t :events/dir-not-found {:base-dir (str base-dir)})
       :exit-code 1}

      :else
      (let [wf-id-str (str workflow-id)
            events    (es/read-workflow-events-by-id base-dir wf-id-str)]
        (cond
          (nil? events)
          {:status    :error
           :message   (messages/t :events/no-events
                                  {:workflow-id wf-id-str
                                   :base-dir    (str base-dir)})
           :exit-code 1}

          raw
          {:status :ok
           :output (with-out-str (pprint/pprint events))}

          :else
          (let [rendered (es/render-timeline events {:gap-threshold-ms (gap-threshold-ms gap-threshold)})]
            {:status :ok
             :output (if (str/blank? rendered)
                       (messages/t :events/no-renderable-events {:workflow-id wf-id-str})
                       rendered)}))))
    (catch Exception e
      {:status :error
       :message (messages/t :events/read-failed {:error (.getMessage e)})
       :exit-code 1})))

;------------------------------------------------------------------------------ Layer 3
;; CLI command entry point

(defn events-show-cmd
  "CLI handler for `miniforge events show <workflow-id>`.

   Accepted opts (injected by babashka.cli dispatch):
     :workflow-id    — required positional UUID string
     :gap-threshold  — integer seconds (default 60); gap detection sensitivity
     :raw            — boolean; dump parsed events as EDN instead of timeline

   Exits 0 on success, 1 on any error."
  [opts]
  (let [{:keys [workflow-id gap-threshold raw]} opts]
    (if (str/blank? workflow-id)
      (do
        (display/print-error (messages/t :events/usage))
        (shared/exit! 1))
      (let [events-dir (app-config/events-dir)
            result     (events-show events-dir workflow-id
                                    {:gap-threshold gap-threshold
                                     :raw           (boolean raw)})]
        (case (:status result)
          :ok
          (println (:output result))

          :error
          (do
            (display/print-error (:message result))
            (shared/exit! (get result :exit-code 1))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Try against your local event store
  (events-show-cmd {:workflow-id "paste-your-uuid-here"})

  ;; Raw EDN dump
  (events-show-cmd {:workflow-id "paste-your-uuid-here" :raw true})

  ;; Tight gap threshold (5 s)
  (events-show-cmd {:workflow-id "paste-your-uuid-here" :gap-threshold 5})

  :end)
