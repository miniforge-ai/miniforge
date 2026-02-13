(ns ai.miniforge.pr-train.core
  "Core implementation of PR Train management.
   Provides PRTrainManager protocol and in-memory implementation."
  (:require
   [ai.miniforge.pr-train.state :as state]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol definition

(defprotocol PRTrainManager
  "Protocol for managing PR trains.

   PR trains are coordinated sets of related PRs that must merge
   in topological order based on repository dependencies."

  ;; Lifecycle
  (create-train [this name dag-id description]
    "Create a new PR train linked to a repo DAG.
     Returns train-id.")

  (add-pr [this train-id repo pr-number url branch title]
    "Add a PR to the train. Assigns next merge-order.
     Returns updated train or nil if train not found.")

  (remove-pr [this train-id pr-number]
    "Remove a PR from the train. Reorders remaining PRs.
     Returns updated train or nil if train/PR not found.")

  (link-prs [this train-id]
    "Auto-compute depends-on/blocks relationships from merge order.
     Returns updated train.")

  ;; State management
  (sync-pr-status [this train-id status-map]
    "Update status for all PRs from external source.
     status-map: {pr-number {:status :ci-status :gate-results}}
     Returns updated train.")

  (update-pr-status [this train-id pr-number new-status]
    "Update status for a single PR.
     Returns updated train or nil if invalid transition.")

  (update-pr-ci-status [this train-id pr-number ci-status]
    "Update CI status for a single PR.
     Returns updated train.")

  ;; Queries
  (get-train [this train-id]
    "Retrieve a train by ID.
     Returns train or nil if not found.")

  (get-blocking [this train-id]
    "Return PRs that are blocking train progress.
     Returns vector of PR numbers.")

  (get-ready-to-merge [this train-id]
    "Return PRs that can merge now.
     Returns vector of PR numbers in merge order.")

  (get-progress [this train-id]
    "Return progress summary.
     Returns {:total :merged :approved :pending :failed}.")

  ;; Actions
  (merge-next [this train-id]
    "Mark the next ready PR as merging.
     Returns {:pr-number :train} or nil if none ready.
     NOTE: Actual merge is performed by adapter.")

  (complete-merge [this train-id pr-number]
    "Mark a PR merge as complete (transition to merged).
     Returns updated train.")

  (fail-merge [this train-id pr-number reason]
    "Mark a PR merge as failed.
     Returns updated train.")

  (pause-train [this train-id reason]
    "Pause all train activity. Sets status to blocked state.
     Returns updated train.")

  (resume-train [this train-id]
    "Resume paused train.
     Returns updated train.")

  (rollback [this train-id trigger reason]
    "Plan rollback: compute which PRs to revert.
     Returns updated train with rollback plan.")

  (execute-rollback-step [this train-id pr-number]
    "Mark a PR as reverted during rollback.
     Returns updated train.")

  (complete-rollback [this train-id]
    "Mark rollback as complete.
     Returns updated train.")

  (abandon-train [this train-id reason]
    "Mark train as abandoned.
     Returns updated train.")

  ;; Evidence
  (generate-evidence-bundle [this train-id]
    "Create evidence bundle from train state.
     Returns EvidenceBundle.")

  (get-evidence-bundle [this bundle-id]
    "Retrieve evidence bundle by ID.
     Returns bundle or nil if not found."))

;------------------------------------------------------------------------------ Layer 1
;; In-memory implementation

(defrecord InMemoryPRTrainManager [trains evidence-bundles]
  PRTrainManager

  ;; Lifecycle
  (create-train [_this name dag-id description]
    (let [train-id (random-uuid)
          train (state/create-train-state train-id name dag-id
                                          :description description)]
      (swap! trains assoc train-id train)
      train-id))

  (add-pr [_this train-id repo pr-number url branch title]
    (when-let [train (get @trains train-id)]
      (let [merge-order (inc (count (:train/prs train)))
            pr (state/create-pr-state repo pr-number url branch title merge-order)
            updated (-> train
                        (update :train/prs conj pr)
                        state/recompute-train-state)]
        (swap! trains assoc train-id updated)
        updated)))

  (remove-pr [_this train-id pr-number]
    (when-let [train (get @trains train-id)]
      (let [prs (:train/prs train)
            filtered (remove #(= pr-number (:pr/number %)) prs)
            ;; Reorder remaining PRs
            reordered (vec (map-indexed
                           (fn [idx pr]
                             (assoc pr :pr/merge-order (inc idx)))
                           (sort-by :pr/merge-order filtered)))
            updated (-> train
                        (assoc :train/prs reordered)
                        state/link-pr-dependencies)]
        (swap! trains assoc train-id updated)
        updated)))

  (link-prs [_this train-id]
    (when-let [train (get @trains train-id)]
      (let [updated (state/link-pr-dependencies train)]
        (swap! trains assoc train-id updated)
        updated)))

  ;; State management
  (sync-pr-status [_this train-id status-map]
    (when-let [train (get @trains train-id)]
      (let [updated (reduce
                     (fn [t [pr-num updates]]
                       (state/update-pr t pr-num
                                        (fn [pr]
                                          (merge pr
                                                 (select-keys updates
                                                              [:pr/status :pr/ci-status
                                                               :pr/gate-results])))))
                     train
                     status-map)
            final (-> updated
                      state/recompute-train-state
                      state/auto-transition-train)]
        (swap! trains assoc train-id final)
        final)))

  (update-pr-status [_this train-id pr-number new-status]
    (when-let [train (get @trains train-id)]
      (when-let [updated (state/transition-pr-status train pr-number new-status)]
        (let [final (-> updated
                        state/recompute-train-state
                        state/auto-transition-train)]
          (swap! trains assoc train-id final)
          final))))

  (update-pr-ci-status [_this train-id pr-number ci-status]
    (when-let [train (get @trains train-id)]
      (let [updated (-> train
                        (state/update-pr pr-number #(assoc % :pr/ci-status ci-status))
                        state/recompute-train-state)]
        (swap! trains assoc train-id updated)
        updated)))

  ;; Queries
  (get-train [_this train-id]
    (get @trains train-id))

  (get-blocking [_this train-id]
    (when-let [train (get @trains train-id)]
      (:train/blocking-prs train)))

  (get-ready-to-merge [_this train-id]
    (when-let [train (get @trains train-id)]
      (:train/ready-to-merge train)))

  (get-progress [_this train-id]
    (when-let [train (get @trains train-id)]
      (:train/progress train)))

  ;; Actions
  (merge-next [_this train-id]
    (when-let [train (get @trains train-id)]
      (when-let [pr-number (first (:train/ready-to-merge train))]
        (when-let [updated (state/transition-pr-status train pr-number :merging)]
          (let [final (-> updated
                          state/recompute-train-state
                          state/auto-transition-train)]
            (swap! trains assoc train-id final)
            {:pr-number pr-number :train final})))))

  (complete-merge [_this train-id pr-number]
    (when-let [train (get @trains train-id)]
      (when-let [updated (state/transition-pr-status train pr-number :merged)]
        (let [final (-> updated
                        state/recompute-train-state
                        state/auto-transition-train)]
          (swap! trains assoc train-id final)
          final))))

  (fail-merge [_this train-id pr-number _reason]
    (when-let [train (get @trains train-id)]
      (let [updated (-> train
                        (state/update-pr pr-number
                                         #(assoc % :pr/status :failed))
                        (state/compute-rollback-plan :ci-failure :pause)
                        state/recompute-train-state
                        state/auto-transition-train)]
        (swap! trains assoc train-id updated)
        updated)))

  (pause-train [_this train-id reason]
    (when-let [train (get @trains train-id)]
      (let [updated (-> train
                        (assoc :train/paused? true)
                        (assoc :train/pause-reason reason)
                        state/update-timestamp)]
        (swap! trains assoc train-id updated)
        updated)))

  (resume-train [_this train-id]
    (when-let [train (get @trains train-id)]
      (let [updated (-> train
                        (dissoc :train/paused? :train/pause-reason)
                        state/recompute-train-state)]
        (swap! trains assoc train-id updated)
        updated)))

  (rollback [_this train-id trigger reason]
    (when-let [train (get @trains train-id)]
      (let [updated (-> train
                        (state/compute-rollback-plan trigger :revert-all)
                        (assoc :train/rollback-reason reason))]
        (swap! trains assoc train-id updated)
        updated)))

  (execute-rollback-step [_this train-id pr-number]
    (when-let [train (get @trains train-id)]
      (let [updated (state/update-pr train pr-number
                                     #(assoc % :pr/rolled-back? true))]
        (swap! trains assoc train-id updated)
        updated)))

  (complete-rollback [_this train-id]
    (when-let [train (get @trains train-id)]
      (when-let [updated (state/transition-train-status train :rolled-back)]
        (swap! trains assoc train-id updated)
        updated)))

  (abandon-train [_this train-id reason]
    (when-let [train (get @trains train-id)]
      (when-let [updated (state/transition-train-status train :abandoned)]
        (let [final (assoc updated :train/abandon-reason reason)]
          (swap! trains assoc train-id final)
          final))))

  ;; Evidence
  (generate-evidence-bundle [_this train-id]
    (when-let [train (get @trains train-id)]
      (let [bundle-id (random-uuid)
            now (java.util.Date.)
            prs (:train/prs train)
            pr-evidence (mapv (fn [pr]
                                {:pr/repo (:pr/repo pr)
                                 :pr/number (:pr/number pr)
                                 :evidence/artifacts
                                 (vec
                                  (concat
                                   (when-let [gates (:pr/gate-results pr)]
                                     (mapv (fn [g]
                                             {:type :gate-results
                                              :content (pr-str g)
                                              :hash (str (hash g))
                                              :timestamp (or (:gate/timestamp g) now)})
                                           gates))
                                   (when-let [intent (:pr/intent pr)]
                                     [{:type :intent-validation
                                       :content (pr-str intent)
                                       :hash (str (hash intent))
                                       :timestamp now}])
                                   (when (#{:approved :merged} (:pr/status pr))
                                     [{:type :approval-record
                                       :content (str "PR " (:pr/number pr) " approved")
                                       :hash (str (hash pr))
                                       :timestamp now}])))})
                              prs)
            all-gates (mapcat :pr/gate-results prs)
            gates-passed (count (filter :gate/passed? all-gates))
            gates-failed (count (remove :gate/passed? all-gates))
            approvals (count (filter #(#{:approved :merged} (:pr/status %)) prs))
            semantic-violations 0
            bundle {:evidence/id bundle-id
                    :evidence/train-id train-id
                    :evidence/created-at now
                    :evidence/prs pr-evidence
                    :evidence/summary {:total-prs (count prs)
                                       :gates-passed gates-passed
                                       :gates-failed gates-failed
                                       :human-approvals approvals
                                       :semantic-violations semantic-violations}
                    :evidence/miniforge-version "0.1.0"}]
        (swap! evidence-bundles assoc bundle-id bundle)
        (swap! trains assoc-in [train-id :train/evidence-bundle-id] bundle-id)
        bundle)))

  (get-evidence-bundle [_this bundle-id]
    (get @evidence-bundles bundle-id)))

;------------------------------------------------------------------------------ Layer 2
;; Factory function

(defn create-manager
  "Create a new in-memory PR train manager.

   Returns an InMemoryPRTrainManager instance."
  []
  (->InMemoryPRTrainManager (atom {}) (atom {})))

;------------------------------------------------------------------------------ Layer 3
;; Utility functions for working with trains

(defn list-trains
  "List all trains in a manager.

   Returns full train maps."
  [manager]
  (->> @(:trains manager)
       vals
       (sort-by :train/created-at)
       vec))

(defn find-trains-by-status
  "Find trains with a given status.

   Returns vector of train-ids."
  [manager status]
  (->> @(:trains manager)
       (filter (fn [[_id train]] (= status (:train/status train))))
       (map first)
       vec))

(defn find-trains-by-dag
  "Find trains using a specific DAG.

   Returns vector of train-ids."
  [manager dag-id]
  (->> @(:trains manager)
       (filter (fn [[_id train]] (= dag-id (:train/dag-id train))))
       (map first)
       vec))

(defn train-contains-pr?
  "Check if a train contains a specific PR."
  [manager train-id repo pr-number]
  (when-let [train (get-train manager train-id)]
    (some #(and (= repo (:pr/repo %))
                (= pr-number (:pr/number %)))
          (:train/prs train))))

(defn get-pr-from-train
  "Get a PR from a train.

   Returns TrainPR or nil if not found."
  [manager train-id pr-number]
  (when-let [train (get-train manager train-id)]
    (state/find-pr train pr-number)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def mgr (create-manager))
  (def train-id (create-train mgr "Add Auth" (random-uuid) "Authentication feature"))
  (add-pr mgr train-id "acme/modules" 100
          "https://github.com/acme/modules/pull/100"
          "feat/auth" "Add auth module")
  (add-pr mgr train-id "acme/live" 200
          "https://github.com/acme/live/pull/200"
          "feat/auth" "Deploy auth")
  (link-prs mgr train-id)
  (get-train mgr train-id)
  (update-pr-status mgr train-id 100 :open)
  (update-pr-status mgr train-id 100 :reviewing)
  (update-pr-status mgr train-id 100 :approved)
  (update-pr-ci-status mgr train-id 100 :passed)
  (get-ready-to-merge mgr train-id)
  (merge-next mgr train-id)
  (complete-merge mgr train-id 100)
  (generate-evidence-bundle mgr train-id)
  (list-trains mgr)
  :end)
