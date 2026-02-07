;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.tui-engine.interface.widget
  "Public widget API for tui-engine.

   Re-exports composite widgets so that consuming components can depend
   on the interface boundary rather than internal namespaces."
  (:require
   [ai.miniforge.tui-engine.widget :as widget]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Widgets

(def status-indicator widget/status-indicator)
(def status-text widget/status-text)
(def progress-bar widget/progress-bar)
(def tree widget/tree)
(def kanban widget/kanban)
(def scrollable widget/scrollable)
(def sparkline widget/sparkline)
