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
   [ai.miniforge.tui-views.effect :as effect]
   [ai.miniforge.tui-views.model :as model]))

;------------------------------------------------------------------------------ Layer 2
;; Event stream message handlers

;; Helper functions

(defn find-workflow-idx
  "Find index of workflow with given ID in workflows vector."
  [workflows workflow-id]
  (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
        (map-indexed vector workflows)))

(defn update-workflow-at
  "Update workflow at index using update-fn."
  [model idx update-fn]
  (if idx
    (update-in model [:workflows idx] update-fn)
    model))

(defn update-detail-if-active
  "Apply update-fn to detail if workflow-id matches active detail."
  [model workflow-id update-fn]
  (if (and workflow-id
           (= workflow-id (get-in model [:detail :workflow-id])))
    (update-fn model)
    model))

(defn ensure-workflow
  "Ensure a workflow row exists so updates remain visible even if
   :workflow/started arrives late or is missing."
  [model workflow-id]
  (if (or (nil? workflow-id)
          (find-workflow-idx (:workflows model) workflow-id))
    model
    (update model :workflows conj (model/make-workflow {:id workflow-id
                                                        :status :running}))))

;; Timestamp wrapper

(defn with-timestamp
  "Wrap a handler result with :last-updated timestamp."
  [model]
  (assoc model :last-updated (java.util.Date.)))

;; Event helpers — extracted to avoid cond-> inside -> antipattern

(defn apply-phase-change
  "Update workflow phase in list and detail context."
  [model idx workflow-id phase]
  (as-> model m
    (if idx (assoc-in m [:workflows idx :phase] phase) m)
    (update-detail-if-active m workflow-id
      #(update-in % [:detail :phases] conj {:phase phase :status :running}))))

(defn apply-agent-status-update
  "Update agent status in workflow list and detail context."
  [model idx workflow-id agent status message]
  (let [agent-entry {:status status :message message}]
    (as-> model m
      (if idx (assoc-in m [:workflows idx :agents agent] agent-entry) m)
      (update-detail-if-active m workflow-id
        #(assoc-in % [:detail :current-agent] (assoc agent-entry :agent agent))))))

(defn apply-gate-result
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

(defn link-chain-instance
  "When a chain step is active, record the workflow instance UUID so
   the view layer can show the chain indicator on the correct row."
  [model workflow-id]
  (if (and (:active-chain model)
           (get-in model [:active-chain :current-step])
           (nil? (get-in model [:active-chain :current-step :instance-id])))
    (assoc-in model [:active-chain :current-step :instance-id] workflow-id)
    model))

(defn handle-workflow-added [model {:keys [workflow-id name spec]}]
  (let [wf (model/make-workflow {:id workflow-id
                                  :name (or name (:name spec))
                                  :status :running})]
    (-> model
        (update :workflows conj wf)
        (link-chain-instance workflow-id)
        ;; Populate evidence intent from spec when detail is active
        (update-detail-if-active workflow-id
          (fn [m]
            (if spec
              (assoc-in m [:detail :evidence :intent]
                        {:description (or (:name spec) name)
                         :spec spec})
              m)))
        with-timestamp)))

(defn handle-phase-changed [model {:keys [workflow-id phase]}]
  (let [model (ensure-workflow model workflow-id)
        idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (apply-phase-change idx workflow-id phase)
        with-timestamp)))

(defn handle-phase-done [model {:keys [workflow-id phase outcome artifacts duration-ms]}]
  (let [model (ensure-workflow model workflow-id)
        idx (find-workflow-idx (:workflows model) workflow-id)
        phase-status (case outcome :success :success :failed :failed :success)]
    (-> model
        (update-workflow-at idx #(update % :progress (fn [p] (min 100 (+ (or p 0) 20)))))
        ;; Update phase status in detail
        (update-detail-if-active workflow-id
          (fn [m]
            (let [m (update-in m [:detail :phases]
                      (fn [phases]
                        (mapv (fn [p]
                                (if (= (:phase p) phase)
                                  (assoc p :status phase-status
                                           :duration-ms duration-ms)
                                  p))
                              phases)))]
              ;; Append artifacts from this phase
              (if (seq artifacts)
                (update-in m [:detail :artifacts] into
                  (mapv (fn [a]
                          (if (map? a) (assoc a :phase phase)
                            {:id a :phase phase :type :unknown :name (str a)}))
                        artifacts))
                m))))
        with-timestamp)))

(defn handle-agent-status [model {:keys [workflow-id agent status message]}]
  (let [model (ensure-workflow model workflow-id)
        idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (apply-agent-status-update idx workflow-id agent status message)
        with-timestamp)))

(defn handle-agent-started [model {:keys [workflow-id agent]}]
  (handle-agent-status model {:workflow-id workflow-id :agent agent
                              :status :started :message nil}))

(defn handle-agent-completed [model {:keys [workflow-id agent]}]
  (handle-agent-status model {:workflow-id workflow-id :agent agent
                              :status :completed :message nil}))

(defn handle-agent-failed [model {:keys [workflow-id agent]}]
  (let [agent-kw (or agent :unknown)]
    (-> (handle-agent-status model {:workflow-id workflow-id :agent agent-kw
                                    :status :failed :message nil})
        (assoc :flash-message (str "Agent " (name agent-kw) " failed")))))

(defn handle-agent-output [model {:keys [workflow-id delta]}]
  (-> (ensure-workflow model workflow-id)
      (update-detail-if-active workflow-id
        #(update-in % [:detail :agent-output] str delta))
      with-timestamp))

(defn handle-workflow-done [model {:keys [workflow-id status duration-ms evidence-bundle-id]}]
  (let [model (ensure-workflow model workflow-id)
        idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (update-workflow-at idx #(assoc % :status (or status :success) :progress 100
                                          :duration-ms duration-ms))
        (update-detail-if-active workflow-id
          (fn [m]
            (cond-> m
              evidence-bundle-id
              (assoc-in [:detail :evidence :bundle-id] evidence-bundle-id)
              duration-ms
              (assoc-in [:detail :duration-ms] duration-ms))))
        with-timestamp)))

(defn handle-workflow-failed [model {:keys [workflow-id error]}]
  (let [model (ensure-workflow model workflow-id)
        idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (update-workflow-at idx #(assoc % :status :failed :error error))
        with-timestamp)))

(defn handle-gate-result [model {:keys [workflow-id gate passed?] :as payload}]
  (let [model (ensure-workflow model workflow-id)
        idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (apply-gate-result idx workflow-id gate passed? payload)
        with-timestamp)))

(defn handle-gate-started [model {:keys [workflow-id gate]}]
  (let [model (ensure-workflow model workflow-id)]
    (-> model
        (assoc :flash-message (str "Gate running: " (if gate (name gate) "unknown")))
        with-timestamp)))

(defn handle-tool-invoked [model {:keys [workflow-id agent tool]}]
  (let [model (ensure-workflow model workflow-id)
        idx (find-workflow-idx (:workflows model) workflow-id)
        status-message (str "Tool " (if tool (name tool) "unknown") " invoked")]
    (-> model
        (apply-agent-status-update idx workflow-id (or agent :agent) :tool-running status-message)
        with-timestamp)))

(defn handle-tool-completed [model {:keys [workflow-id agent tool]}]
  (let [model (ensure-workflow model workflow-id)
        idx (find-workflow-idx (:workflows model) workflow-id)
        status-message (str "Tool " (if tool (name tool) "unknown") " completed")]
    (-> model
        (apply-agent-status-update idx workflow-id (or agent :agent) :tool-completed status-message)
        with-timestamp)))

;------------------------------------------------------------------------------ Layer 2b
;; Chain event handlers

(defn handle-chain-started
  "Set active chain in model when a chain begins execution."
  [model {:keys [chain-id step-count]}]
  (-> model
      (assoc :active-chain {:chain-id chain-id
                            :step-count step-count
                            :current-step nil
                            :status :running})
      (assoc :flash-message (str "Chain " (name chain-id)
                                 " started (" step-count " steps)"))
      with-timestamp))

(defn handle-chain-step-started
  "Update active chain with current step info."
  [model {:keys [chain-id step-id step-index workflow-id]}]
  (-> model
      (assoc-in [:active-chain :current-step]
                {:step-id step-id
                 :step-index step-index
                 :workflow-id workflow-id})
      (assoc :flash-message (str "Chain " (name chain-id)
                                 " step " (inc step-index) "/"
                                 (get-in model [:active-chain :step-count])
                                 ": " (name step-id)))
      with-timestamp))

(defn handle-chain-step-completed
  "Mark current chain step as completed."
  [model {:keys [chain-id step-index]}]
  (-> model
      (assoc :flash-message (str "Chain " (name chain-id)
                                 " step " (inc step-index) " completed"))
      with-timestamp))

(defn handle-chain-step-failed
  "Mark current chain step as failed, update chain status."
  [model {:keys [chain-id step-index error]}]
  (-> model
      (assoc-in [:active-chain :status] :failed)
      (assoc :flash-message (str "Chain " (name chain-id)
                                 " step " (inc step-index)
                                 " FAILED: " error))
      with-timestamp))

(defn handle-chain-completed
  "Mark chain as completed and clear active chain."
  [model {:keys [chain-id duration-ms step-count]}]
  (-> model
      (assoc :active-chain nil)
      (assoc :flash-message (str "Chain " (name chain-id)
                                 " completed — " step-count " steps in "
                                 duration-ms "ms"))
      with-timestamp))

(defn handle-chain-failed
  "Mark chain as failed and record error."
  [model {:keys [chain-id failed-step error]}]
  (-> model
      (assoc-in [:active-chain :status] :failed)
      (assoc :flash-message (str "Chain " (name chain-id)
                                 " FAILED at " (when failed-step (name failed-step))
                                 ": " error))
      with-timestamp))

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

(defn pr-match?
  "True when pr matches by [repo, number]."
  [repo number pr]
  (and (= (:pr/repo pr) repo) (= (:pr/number pr) number)))

(defn merge-matching-pr
  "Merge updated fields into the PR matching [repo, number]; pass others through."
  [repo number pr-data prs]
  (mapv (fn [pr]
          (if (pr-match? repo number pr)
            (merge pr (dissoc pr-data :pr/repo :pr/number))
            pr))
        (or prs [])))

(defn remove-matching-pr
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

(defn handle-policy-evaluated
  "Handle result of an :evaluate-policy side effect.
   Merges policy evaluation result into the matching PR in :pr-items
   and updates the active detail if this PR is currently shown."
  [model {:keys [pr-id result]}]
  (let [[repo number] pr-id
        update-policy (fn [pr]
                        (if (and (= (:pr/repo pr) repo)
                                 (= (:pr/number pr) number))
                          (assoc pr :pr/policy result)
                          pr))
        model (update model :pr-items (fn [prs] (mapv update-policy (or prs []))))
        ;; Also update active detail if this PR is currently shown
        active-pr (get-in model [:detail :selected-pr])
        model (if (and active-pr
                       (= (:pr/repo active-pr) repo)
                       (= (:pr/number active-pr) number))
                (assoc-in model [:detail :selected-pr :pr/policy] result)
                model)]
    (-> model
        (assoc :flash-message
               (if (:evaluation/passed? result)
                 "Policy: passed"
                 (str "Policy: "
                      (if (nil? (:evaluation/passed? result))
                        (str "error — " (:evaluation/error result "unknown"))
                        (str "FAILED ("
                             (count (:evaluation/violations result))
                             " violation(s))")))))
        with-timestamp)))

;------------------------------------------------------------------------------ Layer 3b
;; Train event handlers

(defn handle-train-created
  "Handle result of a :create-train side effect."
  [model {:keys [train-id train-name]}]
  (-> model
      (assoc :active-train-id train-id)
      (assoc :flash-message (str "Train created: " (or train-name "Merge Train")))
      with-timestamp))

(defn handle-prs-added-to-train
  "Handle result of :add-to-train side effect.
   Updates detail train data and flashes count."
  [model {:keys [train added]}]
  (-> model
      (assoc-in [:detail :selected-train] train)
      (assoc :flash-message (str "Added " (or added 0) " PR(s) to train"))
      with-timestamp))

(defn handle-merge-started
  "Handle result of :merge-next side effect."
  [model {:keys [pr-number]}]
  (-> model
      (assoc :flash-message (str "Merging PR #" pr-number "..."))
      with-timestamp))

;------------------------------------------------------------------------------ Layer 3c
;; Batch action event handlers

(defn handle-review-completed
  "Handle result of :review-prs side effect.
   Updates PRs with policy results and flashes summary."
  [model {:keys [results]}]
  (let [results-by-id (into {}
                            (map (fn [{:keys [pr-id result]}] [pr-id result]))
                            (or results []))
        updated-prs (mapv (fn [pr]
                            (let [pr-id [(:pr/repo pr) (:pr/number pr)]]
                              (if-let [result (get results-by-id pr-id)]
                                (assoc pr :pr/policy result)
                                pr)))
                          (:pr-items model []))
        passed (count (filter #(get-in % [:result :evaluation/passed?]) results))
        failed (- (count results) passed)]
    (-> model
        (assoc :pr-items updated-prs)
        (assoc :flash-message (str "Review complete: " passed " passed, " failed " with violations"))
        with-timestamp)))

(defn handle-remediation-completed
  "Handle result of :remediate-prs side effect."
  [model {:keys [fixed failed]}]
  (-> model
      (assoc :flash-message (str "Remediation: " (or fixed 0) " fixed, " (or failed 0) " failed"))
      with-timestamp))

(defn handle-decomposition-started
  "Handle result of :decompose-pr side effect."
  [model {:keys [pr-id plan]}]
  (-> model
      (assoc :flash-message (str "Decomposition plan for #" (second pr-id) ": "
                                 (count (:sub-prs plan [])) " sub-PRs proposed"))
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
           :side-effect (effect/sync-prs))
    (assoc model :flash-message (str "Discover failed: " (or error "unknown error")))))

(defn repos-browsed-ok
  "Success path: populate browse-repos cache, reset repo-manager if source
   is :repo-manager, and flash a summary."
  [model {:keys [repos owner provider warnings source]}]
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
    (assoc base :flash-message
           (str "Loaded " (count repo-list) " remote repo(s)"
                " via " provider-name
                (when owner (str " from " owner))
                " — " candidate-count " new candidate(s)"
                (when (seq warnings*)
                  (str " | warnings: " (count warnings*)))))))

(defn repos-browsed-err
  "Failure path: clear loading flag and flash error details."
  [model {:keys [error error-source provider]}]
  (assoc model
         :browse-repos-loading? false
         :flash-message (str "Repo browse failed"
                             (when error-source (str " (" (name error-source) ")"))
                             (when provider (str " [" (name provider) "]"))
                             ": "
                             (or (some-> error str not-empty)
                                 "no error details were provided"))))

(defn handle-repos-browsed
  "Handle result of a :browse-repos side effect.
   Populates :browse-repos cache used by :add-repo completion."
  [model {:keys [success?] :as payload}]
  (-> (if success?
        (repos-browsed-ok model payload)
        (repos-browsed-err model payload))
      with-timestamp))

;------------------------------------------------------------------------------ Layer 4
;; Chat event handlers

(defn handle-chat-response
  "Handle result of a :chat-send side effect.
   Appends assistant message to chat history, clears pending state."
  [model {:keys [content actions workflow-id]}]
  (let [assistant-msg {:role :assistant
                       :content (or content "No response")
                       :timestamp (java.util.Date.)
                       :actions (or actions [])
                       :workflow-id workflow-id}]
    (-> model
        (update-in [:chat :messages] conj assistant-msg)
        (assoc-in [:chat :pending?] false)
        with-timestamp)))

(defn handle-chat-action-result
  "Handle result of a :chat-execute-action side effect.
   Shows result of executing a chat-suggested action."
  [model {:keys [success? message]}]
  (-> model
      (assoc :flash-message (or message (if success? "Action completed" "Action failed")))
      with-timestamp))
