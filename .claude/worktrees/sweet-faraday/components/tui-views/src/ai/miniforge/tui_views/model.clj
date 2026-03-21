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
   :fleet-repos   [...]        ; Configured fleet repositories
    :repo-manager-source kw    ; :fleet or :browse list in Repos view
    :selected-idx  int          ; Selected item in current list
    :scroll-offset int          ; Scroll offset for long lists
    :detail        map          ; Drill-down state for detail views
    :mode          kw           ; :normal :command :search
    :command-buf   str          ; Command/search input buffer
    :search-results [...]       ; Fuzzy search results
    :filtered-indices set       ; Set of matching visible-list indices during search (nil = show all)
    :last-updated  inst         ; Timestamp of last data update
    :flash-message str          ; Temporary status bar message
    :help-visible? bool         ; Whether help overlay is shown
    :theme         kw           ; Active theme name
    :selected-ids    set          ; Set of selected item IDs (UUIDs or composite keys)
    :visual-anchor   int-or-nil   ; Index where visual mode started (nil = not active)
    :confirm         map-or-nil   ; Confirmation prompt {:action :label :ids} or nil
    :active-chain    map-or-nil   ; Active chain {:chain-id :step-count :current-step :status} or nil
    :search-matches  vec          ; Vector of {:line-idx N} match descriptors (find-in-page)
    :search-match-idx int-or-nil} ; Current match index into search-matches (nil = none)"
  []
  {:view          :pr-fleet
   :workflows     []
   :trains        []
   :pr-items      []
   :fleet-repos   []
   :repo-manager-source :fleet
   :selected-idx  0
   :scroll-offset 0
   :detail        {:workflow-id    nil
                   :phases         []
                   :current-agent  nil
                   :agent-output   ""
                   :evidence       nil
                   :artifacts      []
                   :expanded-nodes #{}
                   :focused-pane   0
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
   :theme         :default
   :selected-ids    #{}
   :visual-anchor   nil
   :confirm         nil
   :search-matches  []
   :search-match-idx nil
   ;; Active chain tracking
   :active-chain nil
   ;; PR filter state (:open, :closed, :merged, :all)
   :pr-filter-state :open
   ;; Train state
   :active-train-id nil
   ;; Filter palette
   :active-filter   nil
   ;; Tab completion
   :completions    []
   :completion-idx nil
   :completing?    false
   ;; Browse repos cache (populated by :browse-repos side-effect)
   :browse-repos   []
   :browse-repos-loading? false
   ;; Chat state (active thread — swapped from chat-threads on enter/escape)
   :chat {:messages [] :input-buf "" :context {} :pending? false :suggested-actions []}
   :chat-threads {}          ;; {thread-key -> chat-state} keyed by [:pr repo number] etc.
   :chat-active-key nil
   ;; Workflow→PR reverse index: {[repo pr-number] → workflow-id}
   :workflow-pr-index {}})

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
;; Repo manager helpers

(defn fleet-repos
  "Configured repositories in fleet."
  [model]
  (vec (get model :fleet-repos [])))

(defn browse-candidate-repos
  "Remote browse candidates that are NOT already configured in fleet."
  [model]
  (let [fleet (set (fleet-repos model))]
    (->> (get model :browse-repos [])
         (remove fleet)
         vec)))

(defn repo-manager-items
  "Visible repository rows for repo-manager based on :repo-manager-source."
  [model]
  (if (= :browse (:repo-manager-source model))
    (vec (get model :browse-repos []))
    (fleet-repos model)))

;------------------------------------------------------------------------------ Layer 3
;; View predicates

(def top-level-views
  "Top-level aggregate views reachable by Tab cycling."
  [:pr-fleet :workflow-list :evidence :artifact-browser :dag-kanban :repo-manager])

(def detail-views
  "Detail views entered via Enter from an aggregate."
  [:workflow-detail :pr-detail :train-view])

(def views
  "All available views in canonical order."
  (vec (concat top-level-views detail-views)))

(def view-labels
  {:pr-fleet         "PR Fleet"
   :workflow-list    "Workflows"
   :evidence         "Evidence"
   :artifact-browser "Artifacts"
   :dag-kanban       "DAG Kanban"
   :repo-manager     "Repos"
   :workflow-detail  "Detail"
   :pr-detail        "PR Detail"
   :train-view       "Train"})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (init-model)
  :leave-this-here)
