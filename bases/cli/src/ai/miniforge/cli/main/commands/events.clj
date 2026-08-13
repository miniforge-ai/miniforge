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
  "CLI `events show <workflow-id>` subcommand entry point.

   Parses/validates opts, resolves the events directory, and maps the result
   of `ai.miniforge.cli.main.commands.events.show/events-show` to CLI output
   and exit codes. See that namespace for the read/render behaviour (event
   log resolution, --raw, --gap-threshold).

   Exits 0 on success, 1 when the workflow-id is unknown or the events
   directory is absent."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.events.show :as show]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

;; CLI command entry point
(defn ^{:stratum 0} events-show-cmd
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
            result     (show/events-show events-dir workflow-id
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
