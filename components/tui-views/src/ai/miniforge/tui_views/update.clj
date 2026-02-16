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

(ns ai.miniforge.tui-views.update
  "Pure update function: (model, msg) -> model'.

   All state transitions for the TUI application. Imports handlers from
   stratified sub-namespaces (navigation, events, mode) and provides input
   handling and root update dispatch.

   Layers 4-5: Input handling + root update function."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update.navigation :as nav]
   [ai.miniforge.tui-views.update.events :as events]
   [ai.miniforge.tui-views.update.mode :as mode]
   [ai.miniforge.tui-views.update.command :as command]
   [ai.miniforge.tui-views.update.completion :as completion]
   [ai.miniforge.tui-views.update.selection :as sel]))

;------------------------------------------------------------------------------ Layer 4
;; Input message handling

(defn- extract-key
  "Extract the semantic key from a normalized input event.
   Maps return :key, bare keywords pass through."
  [key]
  (if (map? key) (:key key) key))

(defn- extract-char
  "Extract the raw character from a normalized input event.
   Maps return :char, bare keywords return nil."
  [key]
  (when (map? key) (:char key)))

(defn- in-repo-manager?
  [model]
  (= :repo-manager (:view model)))

(defn- repo-manager-source
  [model]
  (if (= :browse (:repo-manager-source model)) :browse :fleet))

(defn- reset-repo-manager-state
  [model source]
  (assoc model
         :repo-manager-source source
         :selected-idx 0
         :filtered-indices nil
         :search-matches []
         :search-match-idx nil
         :selected-ids #{}
         :visual-anchor nil))

(defn- selected-repos
  [model]
  (->> (sel/effective-ids model)
       (filter string?)
       distinct
       vec))

(defn- repo-manager-open-browse
  [model provider]
  (let [m (reset-repo-manager-state model :browse)]
    (if (:browse-repos-loading? model)
      (assoc m :flash-message "Browsing remote repos...")
      (assoc m
             :side-effect {:type :browse-repos
                           :source :repo-manager
                           :provider provider}
             :browse-repos-loading? true
             :flash-message (str "Browsing " (name provider) " repos...")))))

(defn- repo-manager-add-selected
  [model]
  (let [repos (selected-repos model)]
    (if (empty? repos)
      (assoc model :flash-message "No remote repository selected.")
      (let [before (set (:fleet-repos model))
            m (reduce (fn [acc repo]
                        (command/execute-command acc (str ":add-repo " repo)))
                      model
                      repos)
            after (set (:fleet-repos m))
            added (count (set/difference after before))]
        (if (pos? added)
          (-> (reset-repo-manager-state m :browse)
              (assoc :side-effect {:type :sync-prs}
                     :flash-message (str "Added " added " repo(s) to fleet. Syncing PRs...")))
          (-> (reset-repo-manager-state m :browse)
              (assoc :flash-message "Selected repos already in fleet.")))))))

(defn- repo-manager-request-remove
  [model]
  (let [repos (selected-repos model)]
    (if (empty? repos)
      (assoc model :flash-message "No configured repository selected.")
      (assoc model :confirm
             {:action :remove-repos
              :label "Remove Repositories"
              :ids (set repos)}))))

(defn- number-key-index
  [k]
  (case k
    :key/d1 0
    :key/d2 1
    :key/d3 2
    :key/d4 3
    :key/d5 4
    :key/d6 5
    :key/d7 6
    :key/d8 7
    :key/d9 8
    :key/d0 9
    nil))

(defn- clear-detail-context
  [model]
  (-> model
      (assoc-in [:detail :workflow-id] nil)
      (assoc-in [:detail :selected-pr] nil)
      (assoc-in [:detail :selected-train] nil)))

(defn- switch-numbered-view
  "Switch view by number within the current abstraction level (1-0)."
  [model key]
  (if-let [idx (number-key-index key)]
    (let [level-views (nav/current-level-views model)
          target (nth level-views idx nil)]
      (if target
        (let [m (nav/switch-view model target model/views)]
          (if (= level-views model/top-level-views)
            (clear-detail-context m)
            m))
        model))
    model))

(defn- handle-normal-input [model key]
  (let [k (extract-key key)]
    ;; When help overlay is visible, only allow dismiss keys
    (if (:help-visible? model)
      (case k
        :key/question  (nav/toggle-help model)
        :key/escape    (nav/toggle-help model)
        :key/q         (assoc model :quit? true)
        ;; All other keys blocked while help is showing
        model)
      ;; Normal mode -- vi-style key handling
      (case k
        ;; Navigation (+ visual range update)
        :key/j         (-> (nav/navigate-down model) sel/update-visual-selection)
        :key/k         (-> (nav/navigate-up model) sel/update-visual-selection)
        :key/down      (-> (nav/navigate-down model) sel/update-visual-selection)
        :key/up        (-> (nav/navigate-up model) sel/update-visual-selection)
        :key/g         (-> (nav/navigate-top model) sel/update-visual-selection)
        :key/G         (-> (nav/navigate-bottom model) sel/update-visual-selection)

        ;; Drill in / out
        :key/enter     (if (and (in-repo-manager? model)
                                (= :browse (repo-manager-source model)))
                         (repo-manager-add-selected model)
                         (nav/enter-detail model))
        :key/escape    (cond
                         (:visual-anchor model)          (sel/exit-visual-mode model)
                         (seq (:selected-ids model))     (sel/clear-selection model)
                         (:filtered-indices model)       (assoc model :filtered-indices nil
                                                                      :selected-idx 0)
                         (seq (:search-matches model))   (assoc model :search-matches []
                                                                      :search-match-idx nil)
                         :else                           (nav/go-back model))
        :key/l         (nav/enter-detail model)
        :key/h         (nav/go-back model)

        ;; Mode switching
        :key/colon     (mode/enter-command-mode model)
        :key/slash     (mode/enter-search-mode model)

        ;; Search match navigation (n/N — like vim)
        :key/n         (nav/next-search-match model)
        :key/N         (nav/prev-search-match model)

        ;; Selection
        :key/space     (if (#{:workflow-list :pr-fleet :artifact-browser :train-view :repo-manager}
                            (:view model))
                         (sel/toggle-selection model)
                         (nav/toggle-expand model))
        :key/v         (sel/enter-visual-mode model)
        :key/a         (sel/select-all model)
        :key/c         (sel/clear-selection model)

        ;; Detail sibling navigation (left/right arrows)
        :key/left      (nav/navigate-prev-item model)
        :key/right     (nav/navigate-next-item model)

        ;; Actions & views
        :key/r         (if (#{:pr-fleet :repo-manager} (:view model))
                         (command/execute-command model ":sync")
                         (nav/refresh model))
        :key/s         (if (#{:pr-fleet :repo-manager} (:view model))
                         (command/execute-command model ":sync")
                         model)
        :key/b         (if (in-repo-manager? model)
                         (repo-manager-open-browse model :all)
                         (nav/switch-view model :dag-kanban model/views))
        :key/e         (nav/switch-view model :evidence model/views)
        :key/f         (if (in-repo-manager? model)
                         (-> (reset-repo-manager-state model :fleet)
                             (assoc :flash-message "Showing configured repositories."))
                         model)
        :key/o         (if (in-repo-manager? model)
                         (repo-manager-open-browse model :all)
                         model)
        :key/x         (if (in-repo-manager? model)
                         (if (= :fleet (repo-manager-source model))
                           (repo-manager-request-remove model)
                           (assoc model :flash-message "Switch to fleet (f) to remove repositories."))
                         model)
        :key/question  (nav/toggle-help model)
        :key/tab       (cond
                         ;; In workflow detail sub-view context: cycle sub-panes only
                         ;; (workflow-detail → evidence → artifact-browser → ...)
                         ;; Esc goes back to the aggregate level.
                         (nav/in-detail-subview? model)
                         (nav/cycle-detail-subview model)
                         ;; Other detail views (pr-detail, train-view): cycle panes
                         (some #{(:view model)} model/detail-views)
                         (nav/cycle-pane model)
                         ;; Top-level aggregate views: cycle among them
                         (some #{(:view model)} model/top-level-views)
                         (nav/cycle-top-level-view model)
                         ;; Fallback
                         :else model)
        :key/shift-tab (cond
                         (nav/in-detail-subview? model)
                         (nav/cycle-detail-subview-reverse model)
                         (some #{(:view model)} model/detail-views)
                         (nav/cycle-pane-reverse model)
                         (some #{(:view model)} model/top-level-views)
                         (nav/cycle-top-level-view-reverse model)
                         :else model)
        ;; Number keys switch within current abstraction level:
        ;; top-level (1..N), detail-level (1..N), etc. with 0 as slot 10.
        :key/d1        (switch-numbered-view model k)
        :key/d2        (switch-numbered-view model k)
        :key/d3        (switch-numbered-view model k)
        :key/d4        (switch-numbered-view model k)
        :key/d5        (switch-numbered-view model k)
        :key/d6        (switch-numbered-view model k)
        :key/d7        (switch-numbered-view model k)
        :key/d8        (switch-numbered-view model k)
        :key/d9        (switch-numbered-view model k)
        :key/d0        (switch-numbered-view model k)
        :key/q         (assoc model :quit? true)
        ;; Unknown key -- no-op
        model))))

(defn- handle-command-input [model key]
  (let [k (extract-key key)]
    (cond
      ;; Escape: dismiss completions if open, otherwise exit command mode
      (= k :key/escape)
      (if (:completing? model)
        (completion/dismiss model)
        (mode/exit-mode model))

      ;; Enter: accept completion if popup open with selection, otherwise execute
      (= k :key/enter)
      (if (and (:completing? model) (:completion-idx model))
        (completion/accept model)
        (let [buf (:command-buf model)
              cmd-name (subs buf 1)
              exact-cmd? (and (not (str/blank? cmd-name))
                              (not (str/includes? cmd-name " "))
                              (some #{cmd-name} (command/complete-command-name cmd-name)))
              arg-completion? (when exact-cmd?
                                (let [result (command/compute-completions model (str ":" cmd-name " "))]
                                  (or (seq (:completions result))
                                      (:side-effect result))))]
          (if arg-completion?
            ;; For commands like :theme or :add-repo, Enter opens argument picker.
            (completion/handle-tab model)
            (-> (command/execute-command model (:command-buf model))
                mode/exit-mode))))

      ;; Tab: open or cycle completions
      (= k :key/tab)
      (completion/handle-tab model)

      ;; Shift+Tab: open or reverse-cycle completions
      (= k :key/shift-tab)
      (completion/handle-shift-tab model)

      ;; Arrow keys: navigate completions if popup is open
      (and (:completing? model) (= k :key/down))
      (completion/next-completion model)

      (and (:completing? model) (= k :key/up))
      (completion/prev-completion model)

      ;; Backspace: delete char and dismiss completions
      (= k :key/backspace)
      (-> (mode/command-backspace model)
          completion/dismiss)

      ;; Character input — all chars (mapped and unmapped) carry :char
      :else
      (if-let [ch (extract-char key)]
        (-> (mode/command-append model ch)
            completion/dismiss)
        model))))

(defn- handle-search-input [model key]
  (let [k (extract-key key)]
    (case k
      :key/escape   (mode/exit-mode model)         ;; abort — clear filter
      :key/enter    (mode/confirm-search model)     ;; confirm — keep filter active
      :key/backspace (mode/command-backspace model)
      ;; Character input — all chars (mapped and unmapped) carry :char
      (if-let [ch (extract-char key)]
        (-> model
            (mode/command-append ch)
            mode/compute-search-results)
        model))))

(defn- refresh-add-repo-completions-if-active
  "If command-mode add-repo picker is currently open, refresh its options."
  [model]
  (try
    (let [active? (and (= :command (:mode model))
                       (:completing? model)
                       (str/starts-with? (:command-buf model "") ":add-repo "))]
      (if-not active?
        model
        (let [result (command/compute-completions model (:command-buf model))
              completions (vec (or (:completions result) []))
              n (count completions)
              prior-idx (or (:completion-idx model) 0)]
          (assoc model
                 :completions completions
                 :completion-idx (when (pos? n) (min prior-idx (dec n)))
                 :completing? (pos? n)))))
    (catch Exception _
      model)))

;------------------------------------------------------------------------------ Layer 5
;; Root update function

(defn update-model
  "Root update function for the TUI application.
   Pure: (model, msg) -> model'

   Messages are vectors: [msg-type payload]
   Input messages: [:input key-event]
   Stream messages: [:msg/workflow-added data], [:msg/phase-changed data], etc."
  [model msg]
  (let [[msg-type payload] (if (vector? msg) msg [msg nil])]
    (case msg-type
      ;; User input
      :input
      (if (:confirm model)
        ;; Confirmation prompt active -- only y/n/escape accepted
        (let [k (extract-key payload)]
          (case k
            :key/y     (-> model
                           (command/execute-confirmed-action)
                           (assoc :confirm nil))
            :key/n     (assoc model :confirm nil :flash-message "Cancelled")
            :key/escape (assoc model :confirm nil :flash-message "Cancelled")
            model))
        ;; Normal input routing by mode
        (case (:mode model)
          :normal  (handle-normal-input model payload)
          :command (handle-command-input model payload)
          :search  (handle-search-input model payload)
          model))

      ;; Event stream messages
      :msg/workflow-added   (events/handle-workflow-added model payload)
      :msg/phase-changed    (events/handle-phase-changed model payload)
      :msg/phase-done       (events/handle-phase-done model payload)
      :msg/agent-status     (events/handle-agent-status model payload)
      :msg/agent-output     (events/handle-agent-output model payload)
      :msg/workflow-done    (events/handle-workflow-done model payload)
      :msg/workflow-failed  (events/handle-workflow-failed model payload)
      :msg/gate-result      (events/handle-gate-result model payload)

      ;; PR fleet messages
      :msg/prs-synced       (events/handle-prs-synced model payload)
      :msg/pr-updated       (events/handle-pr-updated model payload)
      :msg/pr-removed       (events/handle-pr-removed model payload)
      :msg/repos-discovered (events/handle-repos-discovered model payload)
      :msg/repos-browsed    (-> (events/handle-repos-browsed model payload)
                                refresh-add-repo-completions-if-active)

      ;; Side-effect error
      :msg/side-effect-error
      (cond-> (assoc model :flash-message
                     (str "Effect error (" (name (or (:type payload) :unknown)) "): "
                          (:error payload)))
        (= :browse-repos (:type payload))
        (assoc :browse-repos-loading? false))

      ;; Tick (for clock/timing updates, currently unused)
      :tick model

      ;; Unknown message -- no-op
      model)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m (model/init-model))

  ;; Navigate
  (-> m
      (update-model [:msg/workflow-added {:workflow-id (random-uuid) :name "test"}])
      (update-model [:msg/workflow-added {:workflow-id (random-uuid) :name "test-2"}])
      (update-model [:input {:key :key/j :char \j}])
      :selected-idx)
  ;; => 1

  :leave-this-here)
