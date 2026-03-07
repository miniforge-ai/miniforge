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

   Keybindings are loaded from config/tui/keybindings.edn as data.
   Each key maps to a semantic :action/* token, and action tokens
   resolve to handler fns via the action registry (parser pattern).

   Layers 4-5: Input handling + root update function."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [ai.miniforge.tui-views.effect :as effect]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update.navigation :as nav]
   [ai.miniforge.tui-views.update.events :as events]
   [ai.miniforge.tui-views.update.mode :as mode]
   [ai.miniforge.tui-views.update.command :as command]
   [ai.miniforge.tui-views.update.completion :as completion]
   [ai.miniforge.tui-views.update.selection :as sel]
   [ai.miniforge.tui-views.update.chat :as chat]))

;------------------------------------------------------------------------------ Layer 4
;; Keybinding configuration (EDN-driven parser pattern)
;;
;; Flow: raw char → key token (input.clj) → action token (keybindings.edn)
;;       → handler fn (action registry below)

(def keybindings
  "Keybinding config loaded from EDN. Maps key tokens to action tokens,
   grouped by mode (:help, :normal, :command, :search, :number-keys)."
  (-> (io/resource "config/tui/keybindings.edn")
      slurp
      edn/read-string))

(def normal-keybindings  (:normal keybindings))
(def help-keybindings    (:help keybindings))
(def command-keybindings (:command keybindings))
(def search-keybindings  (:search keybindings))
(def filter-keybindings  (:filter keybindings))
(def chat-keybindings    (:chat keybindings))
(def number-key->index   (:number-keys keybindings))

;; ── Input event helpers ──

(defn extract-key
  "Extract the semantic key from a normalized input event.
   Maps return :key, bare keywords pass through."
  [key]
  (if (map? key) (:key key) key))

(defn extract-char
  "Extract the raw character from a normalized input event.
   Maps return :char, bare keywords return nil."
  [key]
  (when (map? key) (:char key)))

;; ── Repo manager helpers ──

(defn in-repo-manager?
  [model]
  (= :repo-manager (:view model)))

(defn repo-manager-source
  [model]
  (if (= :browse (:repo-manager-source model)) :browse :fleet))

(defn reset-repo-manager-state
  [model source]
  (assoc model
         :repo-manager-source source
         :selected-idx 0
         :filtered-indices nil
         :search-matches []
         :search-match-idx nil
         :selected-ids #{}
         :visual-anchor nil))

(defn selected-repos
  [model]
  (->> (sel/effective-ids model)
       (filter string?)
       distinct
       vec))

(defn repo-manager-open-browse
  [model provider]
  (let [m (reset-repo-manager-state model :browse)]
    (if (:browse-repos-loading? model)
      (assoc m :flash-message "Browsing remote repos...")
      (assoc m
             :side-effect (effect/browse-repos {:source :repo-manager
                                                :provider provider})
             :browse-repos-loading? true
             :flash-message (str "Browsing " (name provider) " repos...")))))

(defn repo-manager-add-selected
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
              (assoc :side-effect (effect/sync-prs)
                     :flash-message (str "Added " added " repo(s) to fleet. Syncing PRs...")))
          (-> (reset-repo-manager-state m :browse)
              (assoc :flash-message "Selected repos already in fleet.")))))))

(defn repo-manager-request-remove
  [model]
  (let [repos (selected-repos model)]
    (if (empty? repos)
      (assoc model :flash-message "No configured repository selected.")
      (assoc model :confirm
             {:action :remove-repos
              :label "Remove Repositories"
              :ids (set repos)}))))

(defn clear-detail-context
  [model]
  (-> model
      (assoc-in [:detail :workflow-id] nil)
      (assoc-in [:detail :selected-pr] nil)
      (assoc-in [:detail :selected-train] nil)))

(defn switch-numbered-view
  "Switch view by number within the current abstraction level (1-0)."
  [model key]
  (if-let [idx (number-key->index key)]
    (let [level-views (nav/current-level-views model)
          target (nth level-views idx nil)]
      (if target
        (let [m (nav/switch-view model target model/views)]
          (if (= level-views model/top-level-views)
            (clear-detail-context m)
            m))
        model))
    model))

;; ── Navigation with visual selection ──

(defn nav-down-with-visual  [m] (-> (nav/navigate-down m)   sel/update-visual-selection))
(defn nav-up-with-visual    [m] (-> (nav/navigate-up m)     sel/update-visual-selection))
(defn nav-top-with-visual   [m] (-> (nav/navigate-top m)    sel/update-visual-selection))
(defn nav-bottom-with-visual [m] (-> (nav/navigate-bottom m) sel/update-visual-selection))

(def selectable-views
  #{:workflow-list :pr-fleet :artifact-browser :train-view :repo-manager})

;; ── Extracted action handlers ──

(defn nav-to-evidence [model]
  (nav/switch-view model :evidence model/views))

(defn handle-quit [model]
  (assoc model :quit? true))

(defn handle-enter-or-confirm [model]
  (if (and (in-repo-manager? model) (= :browse (repo-manager-source model)))
    (repo-manager-add-selected model)
    (nav/enter-detail model)))

(defn handle-escape-cascade [model]
  (cond
    (:visual-anchor model)        (sel/exit-visual-mode model)
    (seq (:selected-ids model))   (sel/clear-selection model)
    (:active-filter model)        (assoc model :filtered-indices nil :selected-idx 0
                                               :active-filter nil)
    (:filtered-indices model)     (assoc model :filtered-indices nil :selected-idx 0)
    (seq (:search-matches model)) (assoc model :search-matches [] :search-match-idx nil)
    :else                         (nav/go-back model)))

(defn handle-toggle-or-expand [model]
  (if (selectable-views (:view model))
    (sel/toggle-selection model)
    (nav/toggle-expand model)))

(defn handle-chat-or-clear [model]
  (if (#{:pr-fleet :pr-detail} (:view model))
    (chat/enter model)
    (sel/clear-selection model)))

(defn handle-train-view [model]
  (if (:active-train-id model)
    (command/execute-command model ":train")
    (assoc model :flash-message "No active train. Use :create-train NAME")))

(defn handle-refresh-or-sync [model]
  (if (#{:pr-fleet :repo-manager} (:view model))
    (command/execute-command model ":sync")
    (nav/refresh model)))

(defn handle-sync [model]
  (if (#{:pr-fleet :repo-manager} (:view model))
    (command/execute-command model ":sync")
    model))

(defn handle-browse-or-kanban [model]
  (if (in-repo-manager? model)
    (repo-manager-open-browse model :all)
    (nav/switch-view model :dag-kanban model/views)))

(defn handle-fleet-view [model]
  (if (in-repo-manager? model)
    (-> (reset-repo-manager-state model :fleet)
        (assoc :flash-message "Showing configured repositories."))
    model))

(defn handle-open-browse [model]
  (if (in-repo-manager? model)
    (repo-manager-open-browse model :all)
    model))

(defn handle-open-in-browser [model]
  (let [url (case (:view model)
              :pr-fleet  (let [prs (:pr-items model [])
                               visible (if-let [fi (:filtered-indices model)]
                                         (into [] (keep-indexed #(when (contains? fi %1) %2)) prs)
                                         prs)]
                           (:pr/url (get visible (:selected-idx model))))
              :pr-detail (get-in model [:detail :selected-pr :pr/url])
              nil)]
    (if url
      (assoc model :side-effect (effect/open-url url)
                   :flash-message (str "Opening " url "..."))
      (assoc model :flash-message "No URL available for this item"))))

(defn handle-remove-repos [model]
  (if (in-repo-manager? model)
    (if (= :fleet (repo-manager-source model))
      (repo-manager-request-remove model)
      (assoc model :flash-message "Switch to fleet (f) to remove repositories."))
    model))

(defn handle-cycle-tab [model]
  (cond
    (nav/in-detail-subview? model)                (nav/cycle-detail-subview model)
    (some #{(:view model)} model/detail-views)    (nav/cycle-pane model)
    (some #{(:view model)} model/top-level-views) (nav/cycle-top-level-view model)
    :else                                         model))

(defn handle-cycle-tab-reverse [model]
  (cond
    (nav/in-detail-subview? model)                (nav/cycle-detail-subview-reverse model)
    (some #{(:view model)} model/detail-views)    (nav/cycle-pane-reverse model)
    (some #{(:view model)} model/top-level-views) (nav/cycle-top-level-view-reverse model)
    :else                                         model))

(defn handle-select-all [model]
  (if (and (selectable-views (:view model)) (sel/has-selection? model))
    (sel/select-all model)
    model))

(defn handle-enter-visual-mode [model]
  (if (and (selectable-views (:view model)) (sel/has-selection? model))
    (sel/enter-visual-mode model)
    model))

;; ── Action registries ──
;;
;; Maps :action/* tokens → handler (fn [model] -> model').
;; The keybinding EDN maps key tokens → action tokens; these registries
;; resolve action tokens to concrete handler functions.

(def action-handlers
  "Action token → handler function registry.
   Actions that are context-free (same behavior regardless of view)."
  {:action/navigate-down      nav-down-with-visual
   :action/navigate-up        nav-up-with-visual
   :action/navigate-top       nav-top-with-visual
   :action/navigate-bottom    nav-bottom-with-visual
   :action/navigate-prev-item nav/navigate-prev-item
   :action/navigate-next-item nav/navigate-next-item
   :action/enter-detail       nav/enter-detail
   :action/go-back            nav/go-back
   :action/enter-command-mode mode/enter-command-mode
   :action/enter-search-mode  mode/enter-search-mode
   :action/enter-filter-mode  mode/enter-filter-mode
   :action/next-search-match  nav/next-search-match
   :action/prev-search-match  nav/prev-search-match
   :action/clear-selection    sel/clear-selection
   :action/evidence-view      nav-to-evidence
   :action/toggle-help        nav/toggle-help
   :action/quit               handle-quit})

(def context-action-handlers
  "Action token → handler for actions that depend on view context."
  {:action/enter-or-confirm   handle-enter-or-confirm
   :action/escape-cascade     handle-escape-cascade
   :action/toggle-or-expand   handle-toggle-or-expand
   :action/chat-or-clear      handle-chat-or-clear
   :action/train-view         handle-train-view
   :action/refresh-or-sync    handle-refresh-or-sync
   :action/sync               handle-sync
   :action/browse-or-kanban   handle-browse-or-kanban
   :action/fleet-view         handle-fleet-view
   :action/open-browse        handle-open-browse
   :action/open-in-browser    handle-open-in-browser
   :action/remove-repos       handle-remove-repos
   :action/cycle-tab          handle-cycle-tab
   :action/cycle-tab-reverse  handle-cycle-tab-reverse
   :action/select-all         handle-select-all
   :action/enter-visual-mode  handle-enter-visual-mode})

(defn resolve-action
  "Look up handler fn for an action token."
  [action]
  (or (action-handlers action)
      (context-action-handlers action)))

;; ── Normal mode input ──

(defn handle-normal-input [model key]
  (let [k (extract-key key)]
    (if (:help-visible? model)
      ;; Help overlay: only help-keybinding actions accepted
      (if-let [action (help-keybindings k)]
        (if-let [handler (resolve-action action)]
          (handler model)
          model)
        model)
      ;; Normal mode: resolve key → action → handler, then number keys
      (if-let [action (normal-keybindings k)]
        (if-let [handler (resolve-action action)]
          (handler model)
          model)
        (if (number-key->index k)
          (switch-numbered-view model k)
          model)))))

;; ── Command mode input ──

(defn command-escape
  "Escape in command mode: dismiss completions if open, else exit."
  [model]
  (if (:completing? model)
    (completion/dismiss model)
    (mode/exit-mode model)))

(defn command-enter
  "Enter in command mode: accept completion, open arg picker, or execute."
  [model]
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
        (completion/handle-tab model)
        (-> (command/execute-command model buf)
            mode/exit-mode)))))

(def command-action-handlers
  "Action token → handler for command-mode actions."
  {:action/command-escape       command-escape
   :action/command-enter        command-enter
   :action/completion-tab       completion/handle-tab
   :action/completion-shift-tab completion/handle-shift-tab
   :action/command-backspace    #(-> (mode/command-backspace %) completion/dismiss)
   :action/completion-next      completion/next-completion
   :action/completion-prev      completion/prev-completion})

(defn handle-command-input [model key]
  (let [k (extract-key key)]
    (if-let [action (command-keybindings k)]
      (let [handler (command-action-handlers action)]
        (cond
          ;; Completion arrow keys only active when completing
          (and (#{:action/completion-next :action/completion-prev} action)
               (not (:completing? model)))
          model

          handler
          (handler model)

          :else model))
      ;; No keybinding — character input
      (if-let [ch (extract-char key)]
        (-> (mode/command-append model ch)
            completion/dismiss)
        model))))

(def search-action-handlers
  "Action token → handler for search-mode actions."
  {:action/exit-mode        mode/exit-mode
   :action/confirm-search   mode/confirm-search
   :action/search-backspace mode/command-backspace})

(defn handle-search-input [model key]
  (let [k (extract-key key)]
    (if-let [action (search-keybindings k)]
      (if-let [handler (search-action-handlers action)]
        (handler model)
        model)
      ;; Character input — all chars (mapped and unmapped) carry :char
      (if-let [ch (extract-char key)]
        (-> model
            (mode/command-append ch)
            mode/compute-search-results)
        model))))

;; ── Filter mode input ──

(def filter-action-handlers
  "Action token → handler for filter-mode actions."
  {:action/filter-escape    mode/filter-escape
   :action/filter-confirm   mode/filter-confirm
   :action/filter-backspace mode/filter-backspace})

(defn handle-filter-input [model key]
  (let [k (extract-key key)]
    (if-let [action (filter-keybindings k)]
      (if-let [handler (filter-action-handlers action)]
        (handler model)
        model)
      ;; Character input — append and live-filter
      (if-let [ch (extract-char key)]
        (mode/filter-append model ch)
        model))))

;; ── Chat mode input ──

(def chat-action-handlers
  "Action token → handler for chat-mode actions."
  {:action/chat-escape    chat/escape
   :action/chat-send      chat/send-message
   :action/chat-backspace chat/backspace})

(defn handle-chat-input [model key]
  (let [k (extract-key key)]
    (if-let [action (chat-keybindings k)]
      (if-let [handler (chat-action-handlers action)]
        (handler model)
        model)
      ;; Character input — append to chat buffer
      (if-let [ch (extract-char key)]
        (chat/append model ch)
        model))))

(defn refresh-add-repo-completions-if-active
  "If command-mode add-repo picker is currently open, refresh its options."
  [model]
  (try
    (let [active? (and (= :command (:mode model))
                       (:completing? model)
                       (str/starts-with? (:command-buf model "") ":add-repo "))]
      (if-not active?
        model
        (let [result (command/compute-completions model (:command-buf model))
              completions (vec (get result :completions []))
              n (count completions)
              prior-idx (get model :completion-idx 0)]
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
          :filter  (handle-filter-input model payload)
          :chat    (handle-chat-input model payload)
          model))

      ;; Event stream messages
      :msg/workflow-added   (events/handle-workflow-added model payload)
      :msg/phase-changed    (events/handle-phase-changed model payload)
      :msg/phase-done       (events/handle-phase-done model payload)
      :msg/agent-started    (events/handle-agent-started model payload)
      :msg/agent-completed  (events/handle-agent-completed model payload)
      :msg/agent-failed     (events/handle-agent-failed model payload)
      :msg/agent-status     (events/handle-agent-status model payload)
      :msg/agent-output     (events/handle-agent-output model payload)
      :msg/workflow-done    (events/handle-workflow-done model payload)
      :msg/workflow-failed  (events/handle-workflow-failed model payload)
      :msg/gate-started     (events/handle-gate-started model payload)
      :msg/gate-result      (events/handle-gate-result model payload)
      :msg/tool-invoked     (events/handle-tool-invoked model payload)
      :msg/tool-completed   (events/handle-tool-completed model payload)

      ;; Chain lifecycle messages
      :msg/chain-started         (events/handle-chain-started model payload)
      :msg/chain-step-started    (events/handle-chain-step-started model payload)
      :msg/chain-step-completed  (events/handle-chain-step-completed model payload)
      :msg/chain-step-failed     (events/handle-chain-step-failed model payload)
      :msg/chain-completed       (events/handle-chain-completed model payload)
      :msg/chain-failed          (events/handle-chain-failed model payload)

      ;; PR fleet messages
      :msg/policy-evaluated (events/handle-policy-evaluated model payload)
      :msg/prs-synced       (events/handle-prs-synced model payload)
      :msg/pr-updated       (events/handle-pr-updated model payload)
      :msg/pr-removed       (events/handle-pr-removed model payload)
      :msg/repos-discovered (events/handle-repos-discovered model payload)
      :msg/repos-browsed    (-> (events/handle-repos-browsed model payload)
                                refresh-add-repo-completions-if-active)

      ;; Train messages
      :msg/train-created         (events/handle-train-created model payload)
      :msg/prs-added-to-train    (events/handle-prs-added-to-train model payload)
      :msg/merge-started         (events/handle-merge-started model payload)

      ;; Batch action messages
      :msg/review-completed      (events/handle-review-completed model payload)
      :msg/remediation-completed (events/handle-remediation-completed model payload)
      :msg/decomposition-started (events/handle-decomposition-started model payload)

      ;; Archival result
      :msg/workflows-archived
      (let [{:keys [archived errors]} payload]
        (-> model
            (update :workflows (fn [wfs] (vec (remove #(= :archived (:status %)) wfs))))
            (assoc :flash-message
                   (if (seq errors)
                     (str "Archived " archived ", " (count errors) " failed")
                     (str "Archived " archived " workflow(s) to disk")))))

      ;; Chat messages
      :msg/chat-response         (events/handle-chat-response model payload)
      :msg/chat-action-result    (events/handle-chat-action-result model payload)

      ;; Side-effect error
      :msg/side-effect-error
      (cond-> (assoc model :flash-message
                     (str "Effect error (" (name (get payload :type :unknown)) "): "
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
