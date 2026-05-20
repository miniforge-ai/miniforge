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
   - Probes archived → live → legacy directory layouts via reader.
   - Renders via timeline/render-timeline (GROUP 3a).
   - Optional --raw flag dumps parsed events as EDN (debug aid).
   - Optional --gap-threshold <seconds> (default 60) tunes gap detection.
   - Exits 0 on success, 1 when the workflow-id is unknown or the
     events directory is absent."
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.event-stream.sinks :as sinks]
   [ai.miniforge.event-stream.reader :as reader]
   [ai.miniforge.event-stream.timeline :as timeline]))

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

(defn- render-events
  "Render `events` to a human-readable timeline string.
   `opts` may contain `:gap-threshold-ms` (long)."
  [events opts]
  (timeline/render-timeline events opts))

;------------------------------------------------------------------------------ Layer 2
;; Core (testable) implementation

(defn events-show
  "Read and render the event log for `workflow-id` under `base-dir`.

   Returns one of:
     {:status :ok     :output <string>}
     {:status :error  :message <string>  :exit-code 1}

   Pure in structure — all IO is isolated to `reader/read-workflow-events-by-id`
   which is easy to stub in tests."
  [base-dir workflow-id {:keys [gap-threshold raw] :as _opts}]
  (let [base-file (if (instance? java.io.File base-dir)
                    base-dir
                    (java.io.File. (str base-dir)))]
    (cond
      (not (.exists base-file))
      {:status    :error
       :message   (str "Events directory not found: " base-dir
                       "\nRun at least one workflow first to create it.")
       :exit-code 1}

      :else
      (let [wf-id-str (str workflow-id)
            events    (reader/read-workflow-events-by-id base-dir wf-id-str)]
        (cond
          (nil? events)
          {:status    :error
           :message   (str "No events found for workflow: " wf-id-str
                           "\nChecked layouts: archived/, live/, and flat under " base-dir)
           :exit-code 1}

          raw
          {:status :ok
           :output (with-out-str (pprint/pprint events))}

          :else
          (let [rendered (render-events events {:gap-threshold-ms (gap-threshold-ms gap-threshold)})]
            (if (str/blank? rendered)
              {:status :ok :output (str "(no renderable events for workflow " wf-id-str ")")}
              {:status :ok :output rendered})))))))

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
        (display/print-error
         (str "Usage: miniforge events show <workflow-id>\n"
              "  --gap-threshold <seconds>   Gap detection threshold (default: 60)\n"
              "  --raw                       Dump parsed events as EDN (debug)"))
        (shared/exit! 1))
      (let [base-dir (sinks/default-events-dir)
            result   (events-show base-dir workflow-id {:gap-threshold gap-threshold
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
