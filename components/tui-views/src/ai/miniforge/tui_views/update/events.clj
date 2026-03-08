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
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.persistence :as persistence]
   [ai.miniforge.tui-views.persistence.pr-cache :as pr-cache]
   [ai.miniforge.tui-views.update.filter :as filter]))

;------------------------------------------------------------------------------ Layer 2
;; Event stream message handlers

;; Helper functions

(defn repo-from-pr-url
  "Extract 'owner/repo' from a GitHub PR URL.
   e.g. 'https://github.com/owner/repo/pull/123' → 'owner/repo'."
  [url]
  (when-let [[_ owner-repo] (re-find #"github\.com/([^/]+/[^/]+)/pull/" (str url))]
    owner-repo))

(defn index-workflow-pr
  "Add a [repo, pr-number] → workflow-id entry to the reverse index.
   No-op when pr-info lacks required fields or repo can't be extracted."
  [model workflow-id pr-info]
  (if-let [repo (and pr-info
                     (:pr-number pr-info)
                     (:pr-url pr-info)
                     (repo-from-pr-url (:pr-url pr-info)))]
    (assoc-in model [:workflow-pr-index [repo (:pr-number pr-info)]] workflow-id)
    model))

(defn annotate-pr-with-workflow
  "Annotate a PR with :pr/workflow-id from the reverse index, if present."
  [wf-pr-idx pr]
  (let [pr-key [(:pr/repo pr) (:pr/number pr)]
        wf-id  (get wf-pr-idx pr-key)]
    (cond-> pr wf-id (assoc :pr/workflow-id wf-id))))

(defn pr-match?
  "True when pr matches by [repo, number]."
  [repo number pr]
  (and (= (:pr/repo pr) repo) (= (:pr/number pr) number)))

(defn assessment->risk-entry
  "Convert a single LLM triage assessment into a [pr-id risk-map] pair."
  [{:keys [id level reason]}]
  [id {:level (keyword level) :reason reason}])

(defn assessments->risk-map
  "Convert a vector of LLM triage assessments into {[repo num] {:level :reason}}."
  [assessments]
  (into {} (map assessment->risk-entry) assessments))

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

(defn update-workflow-snapshot
  "Apply a persisted-detail event reducer to the workflow row snapshot."
  [model idx workflow-id event]
  (if idx
    (update-in model [:workflows idx :detail-snapshot]
               #(persistence/apply-detail-event (or % (persistence/empty-detail workflow-id))
                                                event))
    model))

(defn upsert-workflow
  "Insert a workflow row or merge it into the existing row with the same id."
  [model workflow]
  (if-let [idx (find-workflow-idx (:workflows model) (:id workflow))]
    (assoc-in model [:workflows idx] (merge (get-in model [:workflows idx]) workflow))
    (update model :workflows conj workflow)))

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

(defn apply-evidence-intent
  "Seed evidence intent from workflow spec."
  [model spec name]
  (if spec
    (assoc-in model [:detail :evidence :intent]
              {:description (or (:name spec) name) :spec spec})
    model))

(defn handle-workflow-added [model {:keys [workflow-id name spec]}]
  (let [event (persistence/workflow-started-event workflow-id spec)
        wf (assoc (model/make-workflow {:id workflow-id
                                        :name (or name (:name spec))
                                        :status :running})
                  :detail-snapshot (persistence/apply-detail-event
                                    (persistence/empty-detail workflow-id)
                                    event))]
    (-> model
        (upsert-workflow wf)
        (link-chain-instance workflow-id)
        (update-detail-if-active workflow-id
          #(apply-evidence-intent % spec name))
        with-timestamp)))

(defn handle-phase-changed [model {:keys [workflow-id phase]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (update-workflow-snapshot idx workflow-id
                                  (persistence/phase-started-event workflow-id phase))
        (apply-phase-change idx workflow-id phase)
        with-timestamp)))

(defn update-phase-status
  "Update a phase entry's status and duration in the phases vector."
  [phases phase phase-status duration-ms]
  (mapv (fn [p]
          (if (= (:phase p) phase)
            (assoc p :status phase-status :duration-ms duration-ms)
            p))
        phases))

(defn normalize-artifact
  "Normalize an artifact entry, ensuring it has phase and required keys."
  [artifact phase]
  (persistence/normalize-artifact artifact phase))

(defn apply-phase-completion
  "Update detail model with phase completion data: status and artifacts."
  [model phase phase-status duration-ms artifacts]
  (let [model (update-in model [:detail :phases]
                          update-phase-status phase phase-status duration-ms)]
    (if (seq artifacts)
      (update-in model [:detail :artifacts] into
                 (mapv #(normalize-artifact % phase) artifacts))
      model)))

(defn handle-phase-done [model {:keys [workflow-id phase outcome artifacts duration-ms
                                       tokens cost-usd]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)
        phase-status (case outcome :success :success :failed :failed :success)]
    (-> model
        (update-workflow-snapshot idx workflow-id
                                  (persistence/phase-completed-event workflow-id phase outcome
                                                                     artifacts duration-ms
                                                                     {:tokens tokens
                                                                      :cost-usd cost-usd}))
        (update-workflow-at idx #(update % :progress (fn [p] (min 100 (+ (or p 0) 20)))))
        (update-detail-if-active workflow-id
          #(apply-phase-completion % phase phase-status duration-ms artifacts))
        with-timestamp)))

(defn handle-agent-status [model {:keys [workflow-id agent status message]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (update-workflow-snapshot idx workflow-id
                                  (persistence/agent-status-event workflow-id agent status message))
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
  (-> model
      (update-workflow-snapshot (find-workflow-idx (:workflows model) workflow-id)
                                workflow-id
                                (persistence/agent-chunk-event workflow-id delta))
      (update-detail-if-active workflow-id
        #(update-in % [:detail :agent-output] str delta))
      with-timestamp))

(defn apply-workflow-completion
  "Update detail model with workflow completion data."
  [model evidence-bundle-id duration-ms]
  (cond-> model
    evidence-bundle-id (assoc-in [:detail :evidence :bundle-id] evidence-bundle-id)
    duration-ms        (assoc-in [:detail :duration-ms] duration-ms)))

(defn handle-workflow-done [model {:keys [workflow-id status duration-ms evidence-bundle-id
                                          tokens cost-usd pr-info]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (update-workflow-snapshot idx workflow-id
                                  (persistence/workflow-completed-event workflow-id status
                                                                       duration-ms evidence-bundle-id
                                                                       {:tokens tokens
                                                                        :cost-usd cost-usd}))
        (update-workflow-at idx #(cond-> (assoc % :status (or status :success)
                                                   :progress 100 :duration-ms duration-ms)
                                    pr-info (assoc :pr-info pr-info)))
        (index-workflow-pr workflow-id pr-info)
        (update-detail-if-active workflow-id
          #(apply-workflow-completion % evidence-bundle-id duration-ms))
        with-timestamp)))

(defn handle-workflow-failed [model {:keys [workflow-id error]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (update-workflow-snapshot idx workflow-id
                                  (persistence/workflow-failed-event workflow-id error))
        (update-workflow-at idx #(assoc % :status :failed :error error))
        with-timestamp)))

(defn handle-gate-result [model {:keys [workflow-id gate passed?] :as payload}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (-> model
        (update-workflow-snapshot idx workflow-id
                                  (persistence/gate-event workflow-id gate passed?
                                                          (:event/timestamp payload)))
        (apply-gate-result idx workflow-id gate passed? payload)
        with-timestamp)))

(defn handle-gate-started [model {:keys [gate]}]
  (-> model
      (assoc :flash-message (str "Gate running: " (if gate (name gate) "unknown")))
      with-timestamp))

(defn handle-tool-invoked [model {:keys [workflow-id agent tool]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)
        agent-id (or agent :agent)
        status-message (str "Tool " (if tool (name tool) "unknown") " invoked")]
    (-> model
        (update-workflow-snapshot idx workflow-id
                                  (persistence/agent-status-event workflow-id agent-id
                                                                  :tool-running status-message))
        (apply-agent-status-update idx workflow-id agent-id :tool-running status-message)
        with-timestamp)))

(defn handle-tool-completed [model {:keys [workflow-id agent tool]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)
        agent-id (or agent :agent)
        status-message (str "Tool " (if tool (name tool) "unknown") " completed")]
    (-> model
        (update-workflow-snapshot idx workflow-id
                                  (persistence/agent-status-event workflow-id agent-id
                                                                  :tool-completed status-message))
        (apply-agent-status-update idx workflow-id agent-id :tool-completed status-message)
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

(defn build-pr-summary-for-triage
  "Build a compact text summary of a PR for fleet-level risk triage."
  [pr]
  (let [risk (:pr/risk pr)
        adds (get pr :pr/additions 0)
        dels (get pr :pr/deletions 0)]
    (cond-> (str (:pr/repo pr) "#" (:pr/number pr)
                 " \"" (:pr/title pr) "\""
                 " | " (name (get pr :pr/status :unknown))
                 " | ci:" (name (get pr :pr/ci-status :unknown))
                 " | +" adds "/-" dels
                 (when risk
                   (str " | risk:" (name (get risk :risk/level :unknown))
                        " (" (format "%.0f" (* 100.0 (get risk :risk/score 0))) "%)")))
      (:pr/workflow-id pr) (str " | miniforge-sourced"))))

(defn pr-triage-summary
  "Build a {:id [repo num] :summary str} map for fleet risk triage."
  [pr]
  {:id      [(:pr/repo pr) (:pr/number pr)]
   :summary (build-pr-summary-for-triage pr)})

(defn handle-prs-synced
  "Handle result of a :sync-prs side effect.
   Replaces :pr-items with freshly fetched data.
   Preserves the current selection position, clamping to new bounds."
  [model {:keys [pr-items error]}]
  (let [wf-pr-idx (get model :workflow-pr-index {})
        cache (pr-cache/read-cache)
        raw-prs (mapv (partial annotate-pr-with-workflow wf-pr-idx)
                      (or pr-items []))
        prs (pr-cache/apply-cached-policy raw-prs cache)
        active-pr (get-in model [:detail :selected-pr])
        refreshed-pr (when active-pr
                       (some #(when (pr-match? (:pr/repo active-pr)
                                               (:pr/number active-pr) %)
                                %)
                             prs))
        filtered-indices (when-let [query (:active-filter model)]
                           (filter/compute-filter-indices prs query))
        max-idx (max 0 (dec (if filtered-indices
                              (count filtered-indices)
                              (count prs))))
        clamped-idx (min (get model :selected-idx 0) max-idx)
        updated (-> model
                    (assoc :pr-items prs
                           :filtered-indices filtered-indices
                           :selected-idx clamped-idx)
                    (cond-> refreshed-pr
                      (assoc-in [:detail :selected-pr] refreshed-pr))
                    (assoc :flash-message
                           (cond
                             error
                             (str "PR sync failed: " error)

                             (seq prs)
                             (str "Synced " (count prs) " PRs from "
                                  (count (distinct (map :pr/repo prs))) " repo(s)")

                             :else
                             "No PRs found in fleet repos"))
                    with-timestamp)
        ;; Apply cached agent-risk if model doesn't have any yet
        cached-risk (when (empty? (:agent-risk model))
                      (pr-cache/apply-cached-agent-risk prs cache))
        updated (cond-> updated
                  (seq cached-risk) (assoc :agent-risk cached-risk))
        pr-hash (hash (mapv #(select-keys % [:pr/repo :pr/number :pr/additions
                                              :pr/deletions :pr/status :pr/ci-status])
                            prs))]
    (let [risk-changed? (and (seq prs) (not= pr-hash (:agent-risk-hash model)))
          unevaluated-prs (filterv #(nil? (:pr/policy %)) prs)
          effects (cond-> []
                    risk-changed?
                    (conj (effect/fleet-risk-triage (mapv pr-triage-summary prs)))
                    (seq unevaluated-prs)
                    (conj (effect/batch-evaluate-policy unevaluated-prs)))]
      (cond-> updated
        risk-changed?    (assoc :agent-risk-hash pr-hash)
        (seq effects)    (assoc :side-effects effects)))))

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
    ;; Persist to disk cache (async, non-blocking)
    (pr-cache/persist-policy-result! pr-id result (:pr-items model))
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
    ;; Persist all policy results to disk cache (async)
    (doseq [{:keys [pr-id result]} results]
      (pr-cache/persist-policy-result! pr-id result updated-prs))
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
                      ;; Pre-select fleet repos so they appear checked
                      :selected-ids (set (get base :fleet-repos []))
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
   Appends assistant message to chat history, clears pending state.
   Stores suggested actions for execution via number keys."
  [model {:keys [content actions workflow-id]}]
  (let [actions-vec (or actions [])
        assistant-msg {:role :assistant
                       :content (or content "No response")
                       :timestamp (java.util.Date.)
                       :actions actions-vec
                       :workflow-id workflow-id}]
    (let [updated (-> model
                      (update-in [:chat :messages] conj assistant-msg)
                      (assoc-in [:chat :pending?] false)
                      (assoc-in [:chat :suggested-actions] actions-vec)
                      (assoc-in [:chat :scroll-offset] nil) ;; pin to bottom on new message
                      with-timestamp)
          tk (get updated :chat-active-key)]
      (cond-> updated
        tk (assoc-in [:chat-threads tk] (:chat updated))))))

(defn handle-chat-action-result
  "Handle result of a :chat-execute-action side effect.
   Shows result of executing a chat-suggested action."
  [model {:keys [success? message]}]
  (-> model
      (assoc :flash-message (or message (if success? "Action completed" "Action failed")))
      with-timestamp))

(defn handle-fleet-risk-triaged
  "Handle result of :fleet-risk-triage side effect.
   Merges per-PR agent risk assessments into the model."
  [model {:keys [assessments error]}]
  (if error
    (-> model
        (assoc :flash-message (str "Risk triage: " error))
        with-timestamp)
    (let [risk-map (assessments->risk-map assessments)]
      ;; Persist to disk cache (async, non-blocking)
      (pr-cache/persist-risk-triage! risk-map (:pr-items model))
      (-> model
          (assoc :agent-risk risk-map)
          with-timestamp))))
