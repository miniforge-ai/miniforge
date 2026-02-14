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

(ns ai.miniforge.tui-views.model
  "Application model shape and initialization.

   The model is a plain data map that represents the entire TUI state.
   It is managed by the Elm runtime and updated only through pure
   update functions.")

;------------------------------------------------------------------------------ Layer 0
;; Model shape

(defn init-model
  "Create the initial application model.

   Model shape:
   {:view          kw           ; Active view (see `views` vector below)
    :workflows     [...]        ; Vector of workflow summary maps
    :trains        [...]        ; Vector of train summary maps (N9)
    :pr-items      [...]        ; Flat list of PR work items across trains (N9)
    :selected-idx  int          ; Selected item in current list
    :scroll-offset int          ; Scroll offset for long lists
    :detail        map          ; Drill-down state for detail views
    :mode          kw           ; :normal :command :search
    :command-buf   str          ; Command/search input buffer
    :search-results [...]       ; Fuzzy search results
    :filtered-indices set       ; Set of matching workflow indices during search (nil = show all)
    :last-updated  inst         ; Timestamp of last data update
    :flash-message str          ; Temporary status bar message
    :help-visible? bool         ; Whether help overlay is shown
    :theme         kw}          ; Active theme name"
  []
  {:view          :workflow-list
   :workflows     []
   :trains        []
   :pr-items      []
   :selected-idx  0
   :scroll-offset 0
   :detail        {:workflow-id    nil
                   :phases         []
                   :current-agent  nil
                   :agent-output   ""
                   :evidence       nil
                   :artifacts      []
                   :expanded-nodes #{}
                   :selected-pr    nil
                   :pr-readiness   nil
                   :pr-risk        nil
                   :selected-train nil}
   :mode          :normal
   :command-buf   ""
   :search-results []
   :filtered-indices nil
   :last-updated  nil
   :flash-message nil
   :help-visible? false
   :theme         :default})

;------------------------------------------------------------------------------ Layer 1
;; Workflow data shape

(defn make-workflow
  "Create a workflow summary map for the model."
  [{:keys [id name status phase progress started-at]}]
  {:id           id
   :name         (or name (str "workflow-" (subs (str id) 0 8)))
   :status       (or status :pending)
   :phase        phase
   :progress     (or progress 0)
   :started-at   started-at
   :agents       {}
   :gate-results []})

;------------------------------------------------------------------------------ Layer 2
;; View predicates

(def views
  "All available views in navigation order."
  [:workflow-list :workflow-detail :evidence :artifact-browser :dag-kanban
   :pr-fleet :pr-detail :train-view])

(def view-labels
  {:workflow-list    "Workflows"
   :workflow-detail  "Detail"
   :evidence         "Evidence"
   :artifact-browser "Artifacts"
   :dag-kanban       "DAG Kanban"
   :pr-fleet         "PR Fleet"
   :pr-detail        "PR Detail"
   :train-view       "Train"})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (init-model)
  :leave-this-here)
