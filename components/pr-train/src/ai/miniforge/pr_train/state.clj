(ns ai.miniforge.pr-train.state
  "State machine for PR trains and individual PRs.
   Manages valid transitions and state computations."
  (:require
   [ai.miniforge.pr-train.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; State machine definitions

(def train-transitions
  "Valid train status transitions.
   DRAFTING -> OPEN -> REVIEWING -> MERGING -> MERGED
                                          |
                                    FAILED -> ROLLED-BACK
                                          |
                                    ABANDONED"
  {:drafting    #{:open :abandoned}
   :open        #{:reviewing :abandoned}
   :reviewing   #{:merging :abandoned}
   :merging     #{:merged :failed :abandoned}
   :merged      #{}
   :failed      #{:rolled-back :abandoned}
   :rolled-back #{}
   :abandoned   #{}})

(def pr-transitions
  "Valid PR status transitions."
  {:draft             #{:open :closed}
   :open              #{:reviewing :closed}
   :reviewing         #{:changes-requested :approved :closed}
   :changes-requested #{:reviewing :closed}
   :approved          #{:merging :closed}
   :merging           #{:merged :failed}
   :merged            #{}
   :closed            #{:open}  ; Can reopen
   :failed            #{:open}}) ; Can retry

;------------------------------------------------------------------------------ Layer 1
;; Transition validation

(defn valid-train-transition?
  "Check if a train status transition is valid."
  [from-status to-status]
  (contains? (get train-transitions from-status #{}) to-status))

(defn valid-pr-transition?
  "Check if a PR status transition is valid."
  [from-status to-status]
  (contains? (get pr-transitions from-status #{}) to-status))

(defn terminal-train-status?
  "Check if a train status is terminal (no further transitions)."
  [status]
  (empty? (get train-transitions status #{})))

(defn terminal-pr-status?
  "Check if a PR status is terminal."
  [status]
  (empty? (get pr-transitions status #{})))

;------------------------------------------------------------------------------ Layer 2
;; Train state creation and updates

(defn update-timestamp
  "Update the updated-at timestamp."
  [train]
  (assoc train :train/updated-at (java.util.Date.)))

(defn create-train-state
  "Create initial train state.

   Arguments:
   - train-id: UUID for the train
   - name: Name of the train
   - dag-id: UUID of the repo DAG this train uses

   Returns a new PRTrain map in :drafting status."
  [train-id name dag-id & {:keys [description]}]
  (let [now (java.util.Date.)]
    {:train/id train-id
     :train/name name
     :train/description description
     :train/dag-id dag-id
     :train/status :drafting
     :train/prs []
     :train/blocking-prs []
     :train/ready-to-merge []
     :train/progress nil
     :train/rollback-plan nil
     :train/evidence-bundle-id nil
     :train/created-at now
     :train/updated-at now
     :train/merged-at nil}))

(defn transition-train-status
  "Transition train to a new status if valid.

   Returns updated train or nil if transition is invalid."
  [train new-status]
  (let [current-status (:train/status train)]
    (when (valid-train-transition? current-status new-status)
      (-> train
          (assoc :train/status new-status)
          (cond-> (= new-status :merged)
            (assoc :train/merged-at (java.util.Date.)))
          update-timestamp))))

;------------------------------------------------------------------------------ Layer 3
;; PR state management within train

(defn create-pr-state
  "Create a new PR state for addition to a train.

   Arguments:
   - repo: Repository name (e.g., 'acme/terraform')
   - pr-number: PR number
   - url: Full URL to the PR
   - branch: Branch name
   - title: PR title
   - merge-order: Position in train merge order (1-indexed)

   Returns a TrainPR map."
  [repo pr-number url branch title merge-order]
  {:pr/repo repo
   :pr/number pr-number
   :pr/url url
   :pr/branch branch
   :pr/title title
   :pr/status :draft
   :pr/merge-order merge-order
   :pr/depends-on []
   :pr/blocks []
   :pr/ci-status :pending
   :pr/gate-results nil
   :pr/intent nil})

(defn find-pr
  "Find a PR in the train by number."
  [train pr-number]
  (first (filter #(= pr-number (:pr/number %)) (:train/prs train))))

(defn update-pr
  "Update a PR in the train by number.

   Arguments:
   - train: The PRTrain
   - pr-number: PR number to update
   - update-fn: Function to apply to the PR

   Returns updated train."
  [train pr-number update-fn]
  (-> train
      (update :train/prs
              (fn [prs]
                (mapv (fn [pr]
                        (if (= pr-number (:pr/number pr))
                          (update-fn pr)
                          pr))
                      prs)))
      update-timestamp))

(defn transition-pr-status
  "Transition a PR to a new status if valid.

   Returns updated train or nil if transition is invalid."
  [train pr-number new-status]
  (when-let [pr (find-pr train pr-number)]
    (let [current-status (:pr/status pr)]
      (when (valid-pr-transition? current-status new-status)
        (update-pr train pr-number #(assoc % :pr/status new-status))))))

;------------------------------------------------------------------------------ Layer 4
;; Ready-to-merge computation

(defn pr-merged?
  "Check if a PR is merged."
  [train pr-number]
  (when-let [pr (find-pr train pr-number)]
    (= :merged (:pr/status pr))))

(defn deps-merged?
  "Check if all dependencies of a PR are merged."
  [train pr]
  (every? #(pr-merged? train %) (:pr/depends-on pr)))

(defn gates-passed?
  "Check if all gates have passed for a PR."
  [pr]
  (let [gate-results (:pr/gate-results pr)]
    (or (nil? gate-results)
        (empty? gate-results)
        (every? :gate/passed? gate-results))))

(defn ready-to-merge?
  "Check if a PR is ready to merge.

   A PR is ready when:
   1. All dependencies are merged
   2. PR is approved
   3. CI has passed
   4. All gates have passed"
  [train pr]
  (and (deps-merged? train pr)
       (= :approved (:pr/status pr))
       (= :passed (:pr/ci-status pr))
       (gates-passed? pr)))

(defn compute-ready-to-merge
  "Compute list of PR numbers ready to merge.

   Returns vector of PR numbers sorted by merge order."
  [train]
  (->> (:train/prs train)
       (filter #(ready-to-merge? train %))
       (sort-by :pr/merge-order)
       (mapv :pr/number)))

;------------------------------------------------------------------------------ Layer 5
;; Blocking PR computation

(defn pr-blocking?
  "Check if a PR is blocking train progress.

   A PR is blocking when:
   1. Its dependencies are merged (it could proceed)
   2. But it's not approved, CI hasn't passed, or gates haven't passed"
  [train pr]
  (let [deps-ok? (deps-merged? train pr)
        not-merged? (not= :merged (:pr/status pr))
        not-ready? (not (ready-to-merge? train pr))]
    (and deps-ok? not-merged? not-ready?)))

(defn compute-blocking-prs
  "Compute list of PR numbers that are blocking progress.

   Returns vector of PR numbers sorted by merge order."
  [train]
  (->> (:train/prs train)
       (filter #(pr-blocking? train %))
       (sort-by :pr/merge-order)
       (mapv :pr/number)))

;------------------------------------------------------------------------------ Layer 6
;; Progress computation

(defn compute-progress
  "Compute progress summary for a train.

   Returns a Progress map with counts of PRs in each state."
  [train]
  (let [prs (:train/prs train)
        total (count prs)]
    (when (pos? total)
      (let [merged (count (filter #(= :merged (:pr/status %)) prs))
            approved (count (filter #(= :approved (:pr/status %)) prs))
            failed (count (filter #(= :failed (:pr/status %)) prs))
            pending (- total merged approved failed)]
        {:total total
         :merged merged
         :approved approved
         :pending pending
         :failed failed}))))

;------------------------------------------------------------------------------ Layer 7
;; Aggregate state recomputation

(defn recompute-train-state
  "Recompute all derived state for a train.

   Updates:
   - :train/blocking-prs
   - :train/ready-to-merge
   - :train/progress

   Returns updated train."
  [train]
  (-> train
      (assoc :train/blocking-prs (compute-blocking-prs train))
      (assoc :train/ready-to-merge (compute-ready-to-merge train))
      (assoc :train/progress (compute-progress train))
      update-timestamp))

;------------------------------------------------------------------------------ Layer 8
;; Dependency linking

(defn link-pr-dependencies
  "Link PR dependencies based on merge order.

   PRs depend on all PRs with lower merge order.
   This is a simple linear dependency chain.

   For more complex DAG-based dependencies, use link-prs-from-dag.

   Returns updated train."
  [train]
  (let [prs (:train/prs train)
        pr-numbers (set (map :pr/number prs))
        linked-prs (mapv (fn [pr]
                           (let [order (:pr/merge-order pr)
                                 deps (->> prs
                                           (filter #(< (:pr/merge-order %) order))
                                           (mapv :pr/number))
                                 blocks (->> prs
                                             (filter #(> (:pr/merge-order %) order))
                                             (mapv :pr/number))]
                             (-> pr
                                 (assoc :pr/depends-on (vec deps))
                                 (assoc :pr/blocks (vec blocks)))))
                         prs)]
    (-> train
        (assoc :train/prs linked-prs)
        recompute-train-state)))

;------------------------------------------------------------------------------ Layer 9
;; Rollback planning

(defn compute-rollback-plan
  "Compute a rollback plan for the train.

   Arguments:
   - train: The PRTrain
   - trigger: What triggered the rollback
   - action: What action to take

   Returns updated train with rollback plan."
  [train trigger action]
  (let [merged-prs (->> (:train/prs train)
                        (filter #(= :merged (:pr/status %)))
                        (sort-by :pr/merge-order >)  ; Reverse order
                        (mapv :pr/number))
        checkpoint (when (seq merged-prs)
                     (first (sort merged-prs)))]  ; First merged PR
    (-> train
        (assoc :train/rollback-plan
               {:trigger trigger
                :action action
                :checkpoint checkpoint
                :prs-to-revert merged-prs})
        update-timestamp)))

;------------------------------------------------------------------------------ Layer 10
;; Auto-transition based on PR states

(defn infer-train-status
  "Infer what the train status should be based on PR states.

   Returns suggested new status or nil if no change needed."
  [train]
  (let [prs (:train/prs train)
        current-status (:train/status train)]
    (cond
      ;; All PRs merged -> train is merged
      (and (seq prs)
           (every? #(= :merged (:pr/status %)) prs)
           (not= :merged current-status))
      :merged

      ;; Any PR failed -> train failed
      (and (some #(= :failed (:pr/status %)) prs)
           (not= :failed current-status)
           (not (terminal-train-status? current-status)))
      :failed

      ;; Any PR merging -> train is merging
      (and (some #(= :merging (:pr/status %)) prs)
           (= :reviewing current-status))
      :merging

      ;; Any PR reviewing/approved -> train is reviewing
      (and (some #(#{:reviewing :approved} (:pr/status %)) prs)
           (= :open current-status))
      :reviewing

      ;; Any PR open -> train is open
      (and (some #(= :open (:pr/status %)) prs)
           (= :drafting current-status))
      :open

      :else nil)))

(defn auto-transition-train
  "Automatically transition train status based on PR states.

   Returns updated train or original if no transition needed."
  [train]
  (if-let [new-status (infer-train-status train)]
    (or (transition-train-status train new-status) train)
    train))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a train
  (def my-train
    (create-train-state (random-uuid) "Auth Feature" (random-uuid)))

  ;; Add some PRs
  (def pr1 (create-pr-state "acme/modules" 100
                            "https://github.com/acme/modules/pull/100"
                            "feat/auth" "Add auth module" 1))

  (def pr2 (create-pr-state "acme/live" 200
                            "https://github.com/acme/live/pull/200"
                            "feat/auth" "Deploy auth" 2))

  (def train-with-prs
    (-> my-train
        (update :train/prs conj pr1)
        (update :train/prs conj pr2)
        link-pr-dependencies))

  ;; Check dependencies
  (:pr/depends-on (find-pr train-with-prs 200))
  ;; => [100]

  ;; Simulate approvals and CI
  (def ready-train
    (-> train-with-prs
        (update-pr 100 #(assoc % :pr/status :approved :pr/ci-status :passed))
        recompute-train-state))

  ;; Check ready-to-merge
  (:train/ready-to-merge ready-train)
  ;; => [100]

  ;; Simulate merging PR 100
  (def merged-train
    (-> ready-train
        (transition-pr-status 100 :merging)
        (transition-pr-status 100 :merged)
        (update-pr 200 #(assoc % :pr/status :approved :pr/ci-status :passed))
        recompute-train-state))

  ;; Now PR 200 should be ready
  (:train/ready-to-merge merged-train)
  ;; => [200]

  :end)
