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

(ns ai.miniforge.tui-views.update.command
  "Command mode routing.

   Parses :-prefixed command strings and dispatches to model-modifying
   functions. Pure: (model, cmd-str) -> model'.
   Layer 3."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-views.effect :as effect]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update.selection :as sel]
   [ai.miniforge.pr-sync.interface :as pr-sync]))

;------------------------------------------------------------------------------ Layer 0
;; Command parsing

(defn- parse-command
  "Parse a command string into [cmd-name args-string].
   Strips leading ':' prefix."
  [cmd-str]
  (let [trimmed (str/trim (if (str/starts-with? (or cmd-str "") ":")
                            (subs cmd-str 1)
                            (or cmd-str "")))
        parts (str/split trimmed #"\s+" 2)]
    [(first parts) (second parts)]))

;------------------------------------------------------------------------------ Layer 1
;; Command handlers

(defn- cmd-quit [model _args]
  (assoc model :quit? true))

(defn- cmd-view [model args]
  (if (str/blank? args)
    (assoc model :flash-message
           (str "Views: " (str/join ", " (map name model/views))))
    (let [view-kw (keyword args)]
      (if (some #{view-kw} model/views)
        (assoc model :view view-kw :selected-idx 0 :scroll-offset 0)
        (assoc model :flash-message (str "Unknown view: " args))))))

(defn- cmd-refresh [model _args]
  (assoc model :flash-message "Refreshed" :last-updated (java.util.Date.)))

(defn- cmd-help [model _args]
  (assoc model :help-visible? true))

;------------------------------------------------------------------------------ Layer 1b
;; Fleet management commands

(defn- with-fleet-repos
  [model repos]
  (let [repos* (vec (or repos []))
        max-idx (max 0 (dec (count repos*)))
        idx (or (:selected-idx model) 0)]
    (assoc model
           :fleet-repos repos*
           :selected-idx (min idx max-idx))))

(defn- cmd-add-repo [model args]
  (if (str/blank? args)
    (assoc model :flash-message "Usage: :add-repo owner/name OR :add-repo gitlab:group/name")
    (let [result (pr-sync/add-repo! (str/trim args))]
      (if (:success? result)
        (-> (with-fleet-repos model (:repos result))
            (assoc :flash-message
                   (if (:added? result)
                     (str "Added " (:repo result) " to fleet")
                     (str (:repo result) " already in fleet"))))
        (assoc model :flash-message (str "Error: " (:error result)))))))

(defn- cmd-remove-repo [model args]
  (if (str/blank? args)
    (assoc model :flash-message "Usage: :remove-repo owner/name")
    (let [result (pr-sync/remove-repo! (str/trim args))]
      (if (:success? result)
        (-> (with-fleet-repos model (:repos result))
            (assoc :flash-message
                   (if (:removed? result)
                     (str "Removed " (:repo result) " from fleet")
                     (str (:repo result) " not in fleet"))))
        (assoc model :flash-message (str "Error: " (:error result)))))))

(defn- cmd-sync [model _args]
  (assoc model
         :side-effect (effect/sync-prs)
         :flash-message "Syncing PRs..."))

(def ^:private show-states
  #{"open" "merged" "closed" "all"})

(defn- cmd-show [model args]
  (let [state-str (some-> args str/trim str/lower-case)
        state (if (show-states state-str) (keyword state-str) :open)]
    (assoc model
           :side-effect (effect/sync-prs state)
           :flash-message (str "Loading " (name state) " PRs..."))))

(defn- cmd-repos [model _args]
  (let [repos (or (:fleet-repos model) (pr-sync/get-configured-repos))]
    (if (seq repos)
      (assoc model :flash-message
             (str "Fleet repos (" (count repos) "): "
                  (str/join ", " repos)))
      (assoc model :flash-message "No repos configured. Use :add-repo owner/name"))))

(defn- cmd-discover [model args]
  (let [owner (when-not (str/blank? args) (str/trim args))]
    (assoc model
           :side-effect (effect/discover-repos owner)
           :flash-message (str "Discovering repos"
                               (when owner (str " from " owner)) "..."))))

;------------------------------------------------------------------------------ Layer 1c
;; PR selection helper

(defn- selected-prs
  "Return pr-items whose [repo number] pair is in `ids`."
  [model ids]
  (->> (:pr-items model [])
       (filter #(contains? ids [(:pr/repo %) (:pr/number %)]))
       vec))

;; Train commands

(defn- cmd-create-train [model args]
  (if (str/blank? args)
    (assoc model :flash-message "Usage: :create-train NAME")
    (assoc model
           :side-effect (effect/create-train (str/trim args))
           :flash-message (str "Creating train: " (str/trim args) "..."))))

(defn- cmd-add-to-train [model _args]
  (let [ids (sel/effective-ids model)
        train-id (:active-train-id model)]
    (cond
      (nil? train-id)
      (assoc model :flash-message "No active train. Use :create-train NAME first.")

      (empty? ids)
      (assoc model :flash-message "No PRs selected. Select PRs with Space first.")

      :else
      (let [prs (selected-prs model ids)]
        (assoc model
               :side-effect (effect/add-to-train train-id prs)
               :flash-message (str "Adding " (count prs) " PR(s) to train..."))))))

(defn- cmd-merge-next [model _args]
  (let [train-id (:active-train-id model)]
    (if (nil? train-id)
      (assoc model :flash-message "No active train.")
      (assoc model
             :side-effect (effect/merge-next train-id)
             :flash-message "Merging next ready PR..."))))

(defn- cmd-train [model _args]
  (let [train-id (:active-train-id model)]
    (if (nil? train-id)
      (assoc model :flash-message "No active train. Use :create-train NAME first.")
      (assoc model
             :view :train-view
             :selected-idx 0 :scroll-offset 0
             :selected-ids #{} :visual-anchor nil))))

;------------------------------------------------------------------------------ Layer 2
;; Batch action handlers (destructive actions prompt for confirmation)

(defn- request-confirmation
  "Set confirm state on model for destructive actions."
  [model action label]
  (let [ids (sel/effective-ids model)]
    (if (seq ids)
      (assoc model :confirm {:action action :label label :ids ids})
      (assoc model :flash-message (str "Nothing to " (name action))))))

(defn- cmd-archive [model _args]
  (request-confirmation model :archive "Archive"))

(defn- cmd-delete [model _args]
  (request-confirmation model :delete "Delete"))

(defn- cmd-cancel [model _args]
  (request-confirmation model :cancel "Cancel"))

(defn- cmd-rerun [model _args]
  (let [ids (sel/effective-ids model)]
    (if (seq ids)
      (-> model
          (update :workflows
                  (fn [wfs]
                    (mapv (fn [wf]
                            (if (and (contains? ids (:id wf))
                                     (#{:failed :cancelled} (:status wf)))
                              (assoc wf :status :pending :progress 0)
                              wf))
                          wfs)))
          (assoc :flash-message (str "Rerunning " (count ids) " workflow(s)"))
          sel/clear-selection)
      (assoc model :flash-message "Nothing to rerun"))))

;------------------------------------------------------------------------------ Layer 3
;; Confirmed action execution

(defn- set-status-where
  "Map over workflows, setting :status to `new-status` where the workflow id
   is in `ids` and `pred?` (if supplied) is truthy. Default pred: always true."
  ([wfs ids new-status]
   (set-status-where wfs ids new-status (constantly true)))
  ([wfs ids new-status pred?]
   (mapv (fn [wf]
           (if (and (contains? ids (:id wf)) (pred? wf))
             (assoc wf :status new-status)
             wf))
         wfs)))

(defn- confirm-set-status
  "Confirmed action helper: update matching workflows to `new-status`,
   flash `label`, and clear selection. Optionally accepts a `pred?`
   filter (default: all matched ids)."
  ([model ids label new-status]
   (confirm-set-status model ids label new-status (constantly true)))
  ([model ids label new-status pred?]
   (-> model
       (update :workflows set-status-where ids new-status pred?)
       (assoc :flash-message (str label " " (count ids) " item(s)"))
       sel/clear-selection)))

(defn- confirm-delete
  [model ids]
  (-> model
      (update :workflows (fn [wfs] (vec (remove #(contains? ids (:id %)) wfs))))
      (assoc :flash-message (str "Deleted " (count ids) " item(s)")
             :selected-idx 0)
      sel/clear-selection))

(defn- confirm-remove-repos
  [model ids]
  (let [targets (->> ids (filter string?) distinct vec)
        result (reduce
                (fn [{:keys [repos removed errors]} repo]
                  (let [r (pr-sync/remove-repo! repo)]
                    (if (:success? r)
                      {:repos (:repos r)
                       :removed (+ removed (if (:removed? r) 1 0))
                       :errors errors}
                      {:repos repos
                       :removed removed
                       :errors (conj errors (str repo ": " (or (:error r) "unknown error")))})))
                {:repos (:fleet-repos model) :removed 0 :errors []}
                targets)
        next-model (-> (with-fleet-repos model (:repos result))
                       sel/clear-selection)
        removed (:removed result)
        failures (count (:errors result))]
    (assoc next-model
           :flash-message
           (cond
             (zero? (count targets))
             "No repositories selected for removal."

             (and (pos? removed) (zero? failures))
             (str "Removed " removed " repo(s) from fleet")

             (and (zero? removed) (pos? failures))
             (str "Failed to remove selected repos: "
                  (str/join "; " (:errors result)))

             :else
             (str "Removed " removed " repo(s), " failures " failed")))))

(defn execute-confirmed-action
  "Execute the action stored in :confirm after user presses 'y'.
   Pure: (model) -> model'."
  [model]
  (let [{:keys [action ids]} (:confirm model)]
    (case action
      :delete       (confirm-delete model ids)
      :archive      (confirm-set-status model ids "Archived" :archived)
      :cancel       (confirm-set-status model ids "Cancelled" :cancelled
                                        #(= :running (:status %)))
      :remove-repos (confirm-remove-repos model ids)
      ;; Batch PR actions
      :review       (let [prs (selected-prs model ids)]
                      (-> model
                          (assoc :side-effect (effect/review-prs prs)
                                 :flash-message (str "Reviewing " (count prs) " PR(s)..."))
                          sel/clear-selection))
      :remediate    (let [prs (selected-prs model ids)]
                      (-> model
                          (assoc :side-effect (effect/remediate-prs prs)
                                 :flash-message (str "Remediating " (count prs) " PR(s)..."))
                          sel/clear-selection))
      :decompose    (let [pr (first (selected-prs model ids))]
                      (if pr
                        (-> model
                            (assoc :side-effect (effect/decompose-pr pr)
                                   :flash-message (str "Decomposing PR #" (:pr/number pr) "..."))
                            sel/clear-selection)
                        (assoc model :flash-message "No matching PR found")))
      ;; Unknown action -- no-op
      model)))

;------------------------------------------------------------------------------ Layer 4
;; Completion data helpers

(defn- safe-configured-repos
  []
  (try
    (pr-sync/get-configured-repos)
    (catch Exception _ [])))

(defn- add-repo-completions
  [model]
  (let [local-repos (safe-configured-repos)
        remote-repos (or (:browse-repos model) [])]
    (->> (concat ["browse"] local-repos remote-repos)
         (remove str/blank?)
         distinct
         sort
         vec)))

(defn- remove-repo-completions
  [_]
  (safe-configured-repos))

(defn- maybe-browse-side-effect
  [model cmd-name]
  (when (and (= cmd-name "add-repo")
             (empty? (:browse-repos model))
             (not (:browse-repos-loading? model)))
    (effect/browse-repos {:provider :all})))

;------------------------------------------------------------------------------ Layer 4
;; Command table and dispatch

(def ^:private commands
  {"q"           {:handler cmd-quit        :help "Quit the TUI"}
   "quit"        {:handler cmd-quit        :help "Quit the TUI"}
   "view"        {:handler cmd-view        :help "Switch to view (e.g. :view evidence)"
                  :completions (fn [_] (mapv name model/views))}
   "refresh"     {:handler cmd-refresh     :help "Refresh data"}
   "help"        {:handler cmd-help        :help "Show help overlay"}
   "archive"     {:handler cmd-archive     :help "Archive selected workflows"}
   "delete"      {:handler cmd-delete      :help "Delete selected workflows"}
   "cancel"      {:handler cmd-cancel      :help "Cancel running workflows"}
   "rerun"       {:handler cmd-rerun       :help "Rerun failed workflows"}
   "add-repo"    {:handler cmd-add-repo    :help "Add repo to fleet (e.g. :add-repo owner/name or gitlab:group/name)"
                  :completions add-repo-completions}
   "remove-repo" {:handler cmd-remove-repo :help "Remove repo from fleet"
                  :completions remove-repo-completions}
   "sync"          {:handler cmd-sync          :help "Sync PRs from all fleet repos"}
   "show"          {:handler cmd-show          :help "Show PRs by state (open, merged, closed, all)"
                    :completions (fn [_] ["open" "merged" "closed" "all"])}
   "repos"         {:handler cmd-repos         :help "List configured fleet repos"}
   "discover"      {:handler cmd-discover      :help "Discover repos from org (e.g. :discover my-org)"}
   ;; Train commands
   "create-train"  {:handler cmd-create-train  :help "Create a merge train (e.g. :create-train My Train)"}
   "add-to-train"  {:handler cmd-add-to-train  :help "Add selected PRs to active train"}
   "merge-next"    {:handler cmd-merge-next    :help "Merge next ready PR in active train"}
   "train"         {:handler cmd-train         :help "Switch to train view"}
   ;; Batch actions
   "review"        {:handler (fn [m _] (request-confirmation m :review "Review"))
                    :help "Evaluate policy for selected PRs"}
   "remediate"     {:handler (fn [m _] (request-confirmation m :remediate "Remediate"))
                    :help "Auto-fix policy violations for selected PRs"}
   "decompose"     {:handler (fn [m _]
                               (let [ids (sel/effective-ids m)]
                                 (if (not= 1 (count ids))
                                   (assoc m :flash-message "Decompose requires exactly 1 PR selected.")
                                   (request-confirmation m :decompose "Decompose"))))
                    :help "Decompose a large PR into sub-PRs"}})

(defn execute-command
  "Execute a command string. Returns updated model.
   Pure: (model, cmd-str) -> model'."
  [model cmd-str]
  (let [[cmd-name args] (parse-command cmd-str)]
    (if-let [{:keys [handler]} (get commands cmd-name)]
      (handler model args)
      (assoc model :flash-message (str "Unknown command: " cmd-name)))))

;------------------------------------------------------------------------------ Layer 5
;; Tab-completion support

(defn complete-command-name
  "Return matching command names for a partial input.
   Excludes aliases (e.g. 'q' when 'quit' exists)."
  [partial]
  (let [p (str/lower-case (or partial ""))]
    (->> (keys commands)
         (filterv #(str/starts-with? % p))
         sort
         vec)))

(defn compute-completions
  "Given a command buffer string, return completions for the current argument.
   Returns {:cmd cmd-name :completions [str ...] :partial str} or nil."
  [model cmd-buf]
  (let [[cmd-name partial-arg] (parse-command cmd-buf)
        cmd-entry (get commands cmd-name)]
    (when-let [comp-fn (:completions cmd-entry)]
      (let [all-completions (comp-fn model)
            side-effect (maybe-browse-side-effect model cmd-name)
            filtered (if (str/blank? partial-arg)
                       all-completions
                       (filterv #(str/starts-with?
                                   (str/lower-case %)
                                   (str/lower-case partial-arg))
                                all-completions))]
        {:cmd cmd-name
         :completions (vec filtered)
         :partial (or partial-arg "")
         :side-effect side-effect}))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m (model/init-model))
  (execute-command m ":q")
  (execute-command m ":view evidence")
  (execute-command m ":unknown")
  (complete-command-name "th")
  (compute-completions m ":add-repo ")
  :leave-this-here)
