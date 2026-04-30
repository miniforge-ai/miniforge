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

(ns ai.miniforge.cli.main.commands.tui
  "TUI command — launch standalone TUI monitor."
  (:require
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; TUI command

(defn tui-cmd
  "Launch standalone TUI that monitors workflow event files.
   Tail-follows app event files for live workflow state."
  [opts]
  (display/print-info (messages/t :tui/starting))
  (display/print-info (messages/t :tui/watching {:events-dir (app-config/events-dir)}))
  (let [start-tui! (requiring-resolve 'ai.miniforge.tui-views.interface/start-standalone-tui!)]
    (start-tui! opts)))
