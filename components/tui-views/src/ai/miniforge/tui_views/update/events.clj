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

(ns ai.miniforge.tui-views.update.events
  "Event stream message handlers.

   Pure functions that handle workflow events from the event stream.
   Layer 2."
  (:require
   [ai.miniforge.tui-views.model :as model]))

;------------------------------------------------------------------------------ Layer 2
;; Event stream message handlers

;; Helper functions

(defn- find-workflow-idx
  "Find index of workflow with given ID in workflows vector."
  [workflows workflow-id]
  (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
        (map-indexed vector workflows)))

(defn- update-workflow-at
  "Update workflow at index using update-fn."
  [model idx update-fn]
  (if idx
    (update-in model [:workflows idx] update-fn)
    model))

(defn- update-detail-if-active
  "Apply update-fn to detail if workflow-id matches active detail."
  [model workflow-id update-fn]
  (if (= workflow-id (get-in model [:detail :workflow-id]))
    (update-fn model)
    model))

;; Timestamp wrapper

(defn- with-timestamp
  "Wrap a handler result with :last-updated timestamp."
  [model]
  (assoc model :last-updated (java.util.Date.)))

;; Event helpers — extracted to avoid cond-> inside -> antipattern

(defn- apply-phase-change
  "Update workflow phase in list and detail context."
  [model idx workflow-id phase]
  (as-> model m
    (if idx (assoc-in m [:workflows idx :phase] phase) m)
    (update-detail-if-active m workflow-id
      #(update-in % [:detail :phases] conj {:phase phase :status :running}))))

(defn- apply-agent-status-update
  "Update agent status in workflow list and detail context."
  [model idx workflow-id agent status message]
  (let [agent-entry {:status status :message message}]
    (as-> model m
      (if idx (assoc-in m [:workflows idx :agents agent] agent-entry) m)
      (update-detail-if-active m workflow-id
        #(assoc-in % [:detail :current-agent] (assoc agent-entry :agent agent))))))

(defn- apply-gate-result
  "Record gate result in workflow list, flash on failure, and update detail."
  [model idx workflow-id gate passed? payload]
  (as-> model m
    (if idx (update-in m [:workflows idx :gate-results] conj payload) m)
    (if (not passed?)
      (assoc m :flash-message (str "Gate FAILED: " (when gate (name gate))))
      m)
    (update-detail-if-active m workflow-id
      (fn [m'] (update-in m' [:detail :evidence :validation :results]
                           (fnil conj []) payload)))))

;; Event handlers

(defn handle-workflow-added [model {:keys [workflow-id name spec]}]
  (let [wf (model/make-workflow {:id workflow-id
                                  :name (or name (:name spec))
                                  :status :running})]
    (-> model
        (update :workflows conj wf)
        with-timestamp)))

(defn handle-phase-changed [model {:keys [workflow-id phase]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (apply-phase-change idx workflow-id phase)
        with-timestamp)))

(defn handle-phase-done [model {:keys [workflow-id]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (update-workflow-at idx #(update % :progress (fn [p] (min 100 (+ (or p 0) 20)))))
        with-timestamp)))

(defn handle-agent-status [model {:keys [workflow-id agent status message]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (apply-agent-status-update idx workflow-id agent status message)
        with-timestamp)))

(defn handle-agent-output [model {:keys [workflow-id delta]}]
  (-> model
      (update-detail-if-active workflow-id
        #(update-in % [:detail :agent-output] str delta))
      with-timestamp))

(defn handle-workflow-done [model {:keys [workflow-id status]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (update-workflow-at idx #(assoc % :status (or status :success) :progress 100))
        with-timestamp)))

(defn handle-workflow-failed [model {:keys [workflow-id error]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (update-workflow-at idx #(assoc % :status :failed :error error))
        with-timestamp)))

(defn handle-gate-result [model {:keys [workflow-id gate passed?] :as payload}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (apply-gate-result idx workflow-id gate passed? payload)
        with-timestamp)))

;------------------------------------------------------------------------------ Layer 3
;; PR event handlers

(defn handle-prs-synced
  "Handle result of a :sync-prs side effect.
   Replaces :pr-items with freshly fetched data."
  [model {:keys [pr-items]}]
  (let [prs (or pr-items [])]
    (-> model
        (assoc :pr-items (vec prs))
        (assoc :flash-message (str "Synced " (count prs) " PRs from "
                                   (count (distinct (map :pr/repo prs))) " repo(s)"))
        with-timestamp)))

(defn- pr-match?
  "True when pr matches by [repo, number]."
  [repo number pr]
  (and (= (:pr/repo pr) repo) (= (:pr/number pr) number)))

(defn- merge-matching-pr
  "Merge updated fields into the PR matching [repo, number]; pass others through."
  [repo number pr-data prs]
  (mapv (fn [pr]
          (if (pr-match? repo number pr)
            (merge pr (dissoc pr-data :pr/repo :pr/number))
            pr))
        (or prs [])))

(defn- remove-matching-pr
  "Remove the PR matching [repo, number] from the vector."
  [repo number prs]
  (into [] (remove #(pr-match? repo number %)) (or prs [])))

(defn handle-pr-updated
  "Update a single PR in :pr-items by [repo, number] match."
  [model {:keys [pr/repo pr/number] :as pr-data}]
  (-> model
      (update :pr-items (partial merge-matching-pr repo number pr-data))
      with-timestamp))

(defn handle-pr-removed
  "Remove a PR from :pr-items by [repo, number] match."
  [model {:keys [pr/repo pr/number]}]
  (-> model
      (update :pr-items (partial remove-matching-pr repo number))
      with-timestamp))

(defn handle-repos-discovered
  "Handle result of a :discover-repos side effect.
   Shows discovery results and triggers a PR sync."
  [model {:keys [success? added discovered owner repos error]}]
  (if success?
    (assoc model
           :fleet-repos (vec (or repos (:fleet-repos model) []))
           :flash-message (str "Discovered " discovered " repo(s)"
                               (when owner (str " from " owner))
                               " — " added " new. Syncing PRs...")
           :side-effect {:type :sync-prs})
    (assoc model :flash-message (str "Discover failed: " (or error "unknown error")))))

(defn handle-repos-browsed
  "Handle result of a :browse-repos side effect.
   Populates :browse-repos cache used by :add-repo completion."
  [model {:keys [success? repos owner provider warnings error error-source source]}]
  (if success?
    (let [repo-list (vec (or repos []))
          base (assoc model
                      :browse-repos repo-list
                      :browse-repos-loading? false)
          candidate-count (count (model/browse-candidate-repos base))
          provider-name (name (or provider :github))
          warnings* (vec (or warnings []))
          base (if (= source :repo-manager)
                 (assoc base
                        :repo-manager-source :browse
                        :selected-idx 0
                        :filtered-indices nil
                        :search-matches []
                        :search-match-idx nil
                        :selected-ids #{}
                        :visual-anchor nil)
                 base)]
      (-> base
          (assoc :flash-message
                 (str "Loaded " (count repo-list) " remote repo(s)"
                      " via " provider-name
                      (when owner (str " from " owner))
                      " — " candidate-count " new candidate(s)"
                      (when (seq warnings*)
                        (str " | warnings: " (count warnings*)))))
          with-timestamp))
    (-> model
        (assoc :browse-repos-loading? false
               :flash-message (str "Repo browse failed"
                                   (when error-source (str " (" (name error-source) ")"))
                                   (when provider (str " [" (name provider) "]"))
                                   ": "
                                   (or (some-> error str not-empty)
                                       "no error details were provided")))
        with-timestamp)))
