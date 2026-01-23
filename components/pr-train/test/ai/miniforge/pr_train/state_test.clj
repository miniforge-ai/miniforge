(ns ai.miniforge.pr-train.state-test
  "Tests for pr-train state machine logic."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.state :as state]))

;; ============================================================================
;; State transition validation tests
;; ============================================================================

(deftest train-transitions-test
  (testing "train-transitions map is complete"
    (is (map? state/train-transitions))
    (is (contains? state/train-transitions :drafting))
    (is (contains? state/train-transitions :merged))
    (is (contains? state/train-transitions :abandoned))))

(deftest valid-train-transition?-test
  (testing "valid forward transitions"
    (is (state/valid-train-transition? :drafting :open))
    (is (state/valid-train-transition? :drafting :abandoned))
    (is (state/valid-train-transition? :open :reviewing))
    (is (state/valid-train-transition? :reviewing :merging))
    (is (state/valid-train-transition? :merging :merged))
    (is (state/valid-train-transition? :merging :failed))
    (is (state/valid-train-transition? :failed :rolled-back))
    (is (state/valid-train-transition? :failed :abandoned)))

  (testing "invalid transitions"
    (is (not (state/valid-train-transition? :merged :drafting)))
    (is (not (state/valid-train-transition? :abandoned :drafting)))
    (is (not (state/valid-train-transition? :rolled-back :open)))
    (is (not (state/valid-train-transition? :drafting :merged)))))

(deftest valid-pr-transition?-test
  (testing "valid PR transitions"
    (is (state/valid-pr-transition? :draft :open))
    (is (state/valid-pr-transition? :draft :closed))
    (is (state/valid-pr-transition? :open :reviewing))
    (is (state/valid-pr-transition? :reviewing :changes-requested))
    (is (state/valid-pr-transition? :reviewing :approved))
    (is (state/valid-pr-transition? :changes-requested :reviewing))
    (is (state/valid-pr-transition? :approved :merging))
    (is (state/valid-pr-transition? :merging :merged))
    (is (state/valid-pr-transition? :merging :failed))
    (is (state/valid-pr-transition? :closed :open)))

  (testing "invalid PR transitions"
    (is (not (state/valid-pr-transition? :draft :merged)))
    (is (not (state/valid-pr-transition? :merged :draft)))
    (is (not (state/valid-pr-transition? :open :merged)))
    (is (not (state/valid-pr-transition? :reviewing :merged)))))

(deftest terminal-status-tests
  (testing "terminal train statuses"
    (is (state/terminal-train-status? :merged))
    (is (state/terminal-train-status? :rolled-back))
    (is (state/terminal-train-status? :abandoned))
    (is (not (state/terminal-train-status? :drafting)))
    (is (not (state/terminal-train-status? :failed))))

  (testing "terminal PR statuses"
    (is (state/terminal-pr-status? :merged))
    (is (not (state/terminal-pr-status? :draft)))
    (is (not (state/terminal-pr-status? :failed)))))

;; ============================================================================
;; Train state creation tests
;; ============================================================================

(deftest create-train-state-test
  (testing "creates valid initial state"
    (let [train-id (random-uuid)
          dag-id (random-uuid)
          train (state/create-train-state train-id "My Train" dag-id)]
      (is (= train-id (:train/id train)))
      (is (= "My Train" (:train/name train)))
      (is (= dag-id (:train/dag-id train)))
      (is (= :drafting (:train/status train)))
      (is (empty? (:train/prs train)))
      (is (empty? (:train/blocking-prs train)))
      (is (empty? (:train/ready-to-merge train)))
      (is (nil? (:train/progress train)))
      (is (inst? (:train/created-at train)))
      (is (inst? (:train/updated-at train)))))

  (testing "accepts optional description"
    (let [train (state/create-train-state (random-uuid) "Test" (random-uuid)
                                          :description "A description")]
      (is (= "A description" (:train/description train))))))

(deftest transition-train-status-test
  (testing "valid transition updates status"
    (let [train (state/create-train-state (random-uuid) "Test" (random-uuid))
          updated (state/transition-train-status train :open)]
      (is (some? updated))
      (is (= :open (:train/status updated)))))

  (testing "invalid transition returns nil"
    (let [train (state/create-train-state (random-uuid) "Test" (random-uuid))
          updated (state/transition-train-status train :merged)]
      (is (nil? updated))))

  (testing "transition to merged sets merged-at timestamp"
    (let [train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/status :merging))
          updated (state/transition-train-status train :merged)]
      (is (inst? (:train/merged-at updated))))))

;; ============================================================================
;; PR state creation tests
;; ============================================================================

(deftest create-pr-state-test
  (testing "creates valid PR state"
    (let [pr (state/create-pr-state "acme/repo" 123
                                    "https://github.com/acme/repo/pull/123"
                                    "feat/test" "Test PR" 1)]
      (is (= "acme/repo" (:pr/repo pr)))
      (is (= 123 (:pr/number pr)))
      (is (= "https://github.com/acme/repo/pull/123" (:pr/url pr)))
      (is (= "feat/test" (:pr/branch pr)))
      (is (= "Test PR" (:pr/title pr)))
      (is (= 1 (:pr/merge-order pr)))
      (is (= :draft (:pr/status pr)))
      (is (= :pending (:pr/ci-status pr)))
      (is (empty? (:pr/depends-on pr)))
      (is (empty? (:pr/blocks pr))))))

(deftest find-pr-test
  (testing "finds PR by number"
    (let [pr1 (state/create-pr-state "acme/a" 100 "url1" "branch" "PR 1" 1)
          pr2 (state/create-pr-state "acme/b" 200 "url2" "branch" "PR 2" 2)
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1 pr2]))]
      (is (= pr1 (state/find-pr train 100)))
      (is (= pr2 (state/find-pr train 200)))
      (is (nil? (state/find-pr train 300))))))

(deftest update-pr-test
  (testing "updates PR in train"
    (let [pr1 (state/create-pr-state "acme/a" 100 "url1" "branch" "PR 1" 1)
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1]))
          updated (state/update-pr train 100 #(assoc % :pr/status :open))]
      (is (= :open (:pr/status (state/find-pr updated 100)))))))

(deftest transition-pr-status-test
  (testing "valid PR transition updates status"
    (let [pr (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr]))
          updated (state/transition-pr-status train 100 :open)]
      (is (some? updated))
      (is (= :open (:pr/status (state/find-pr updated 100))))))

  (testing "invalid PR transition returns nil"
    (let [pr (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr]))
          updated (state/transition-pr-status train 100 :merged)]
      (is (nil? updated)))))

;; ============================================================================
;; Ready-to-merge computation tests
;; ============================================================================

(deftest deps-merged?-test
  (testing "PR with no deps is always deps-merged"
    (let [pr (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr]))]
      (is (state/deps-merged? train pr))))

  (testing "PR with unmerged deps is not deps-merged"
    (let [pr1 (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
          pr2 (-> (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
                  (assoc :pr/depends-on [100]))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1 pr2]))]
      (is (not (state/deps-merged? train pr2)))))

  (testing "PR with merged deps is deps-merged"
    (let [pr1 (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
                  (assoc :pr/status :merged))
          pr2 (-> (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
                  (assoc :pr/depends-on [100]))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1 pr2]))]
      (is (state/deps-merged? train pr2)))))

(deftest gates-passed?-test
  (testing "PR with no gates passes"
    (let [pr (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)]
      (is (state/gates-passed? pr))))

  (testing "PR with all gates passed passes"
    (let [pr (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
                 (assoc :pr/gate-results
                        [{:gate/id :security :gate/type :security :gate/passed? true}
                         {:gate/id :lint :gate/type :lint :gate/passed? true}]))]
      (is (state/gates-passed? pr))))

  (testing "PR with failed gate does not pass"
    (let [pr (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
                 (assoc :pr/gate-results
                        [{:gate/id :security :gate/type :security :gate/passed? true}
                         {:gate/id :lint :gate/type :lint :gate/passed? false}]))]
      (is (not (state/gates-passed? pr))))))

(deftest ready-to-merge?-test
  (testing "PR is ready when all conditions met"
    (let [pr (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
                 (assoc :pr/status :approved)
                 (assoc :pr/ci-status :passed))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr]))]
      (is (state/ready-to-merge? train pr))))

  (testing "PR not ready if not approved"
    (let [pr (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
                 (assoc :pr/status :reviewing)
                 (assoc :pr/ci-status :passed))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr]))]
      (is (not (state/ready-to-merge? train pr)))))

  (testing "PR not ready if CI not passed"
    (let [pr (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
                 (assoc :pr/status :approved)
                 (assoc :pr/ci-status :running))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr]))]
      (is (not (state/ready-to-merge? train pr)))))

  (testing "PR not ready if deps not merged"
    (let [pr1 (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
          pr2 (-> (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
                  (assoc :pr/depends-on [100])
                  (assoc :pr/status :approved)
                  (assoc :pr/ci-status :passed))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1 pr2]))]
      (is (not (state/ready-to-merge? train pr2))))))

(deftest compute-ready-to-merge-test
  (testing "returns ready PRs in merge order"
    (let [pr1 (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
                  (assoc :pr/status :approved)
                  (assoc :pr/ci-status :passed))
          pr2 (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1 pr2]))]
      (is (= [100] (state/compute-ready-to-merge train))))))

;; ============================================================================
;; Blocking PR computation tests
;; ============================================================================

(deftest pr-blocking?-test
  (testing "PR is blocking when deps merged but not ready"
    (let [pr (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
                 (assoc :pr/status :open)
                 (assoc :pr/ci-status :passed))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr]))]
      (is (state/pr-blocking? train pr))))

  (testing "Merged PR is not blocking"
    (let [pr (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
                 (assoc :pr/status :merged))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr]))]
      (is (not (state/pr-blocking? train pr)))))

  (testing "Ready PR is not blocking"
    (let [pr (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR" 1)
                 (assoc :pr/status :approved)
                 (assoc :pr/ci-status :passed))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr]))]
      (is (not (state/pr-blocking? train pr))))))

(deftest compute-blocking-prs-test
  (testing "returns blocking PRs"
    (let [pr1 (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
                  (assoc :pr/status :open))
          pr2 (-> (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
                  (assoc :pr/depends-on [100])
                  (assoc :pr/status :approved)
                  (assoc :pr/ci-status :passed))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1 pr2]))]
      (is (= [100] (state/compute-blocking-prs train))))))

;; ============================================================================
;; Progress computation tests
;; ============================================================================

(deftest compute-progress-test
  (testing "computes correct progress"
    (let [pr1 (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
                  (assoc :pr/status :merged))
          pr2 (-> (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
                  (assoc :pr/status :approved))
          pr3 (state/create-pr-state "acme/c" 300 "url" "branch" "PR 3" 3)
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1 pr2 pr3]))
          progress (state/compute-progress train)]
      (is (= 3 (:total progress)))
      (is (= 1 (:merged progress)))
      (is (= 1 (:approved progress)))
      (is (= 1 (:pending progress)))
      (is (= 0 (:failed progress)))))

  (testing "returns nil for empty train"
    (let [train (state/create-train-state (random-uuid) "Test" (random-uuid))]
      (is (nil? (state/compute-progress train))))))

;; ============================================================================
;; Dependency linking tests
;; ============================================================================

(deftest link-pr-dependencies-test
  (testing "links PRs in linear chain"
    (let [pr1 (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
          pr2 (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
          pr3 (state/create-pr-state "acme/c" 300 "url" "branch" "PR 3" 3)
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1 pr2 pr3]))
          linked (state/link-pr-dependencies train)]

      (let [p1 (state/find-pr linked 100)
            p2 (state/find-pr linked 200)
            p3 (state/find-pr linked 300)]
        (is (= [] (:pr/depends-on p1)))
        (is (= [200 300] (:pr/blocks p1)))
        (is (= [100] (:pr/depends-on p2)))
        (is (= [300] (:pr/blocks p2)))
        (is (= [100 200] (:pr/depends-on p3)))
        (is (= [] (:pr/blocks p3)))))))

;; ============================================================================
;; Rollback planning tests
;; ============================================================================

(deftest compute-rollback-plan-test
  (testing "computes rollback plan with merged PRs"
    (let [pr1 (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
                  (assoc :pr/status :merged))
          pr2 (-> (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
                  (assoc :pr/status :merged))
          pr3 (-> (state/create-pr-state "acme/c" 300 "url" "branch" "PR 3" 3)
                  (assoc :pr/status :failed))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1 pr2 pr3]))
          planned (state/compute-rollback-plan train :ci-failure :revert-all)]

      (let [plan (:train/rollback-plan planned)]
        (is (= :ci-failure (:trigger plan)))
        (is (= :revert-all (:action plan)))
        (is (= 100 (:checkpoint plan)))
        (is (= [200 100] (:prs-to-revert plan)))))))

;; ============================================================================
;; Auto-transition tests
;; ============================================================================

(deftest infer-train-status-test
  (testing "infers :merged when all PRs merged"
    (let [pr1 (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
                  (assoc :pr/status :merged))
          pr2 (-> (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
                  (assoc :pr/status :merged))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/status :merging)
                    (assoc :train/prs [pr1 pr2]))]
      (is (= :merged (state/infer-train-status train)))))

  (testing "infers :failed when any PR failed"
    (let [pr1 (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
                  (assoc :pr/status :merged))
          pr2 (-> (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
                  (assoc :pr/status :failed))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/status :merging)
                    (assoc :train/prs [pr1 pr2]))]
      (is (= :failed (state/infer-train-status train)))))

  (testing "infers :reviewing when any PR reviewing/approved"
    (let [pr (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
                 (assoc :pr/status :reviewing))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/status :open)
                    (assoc :train/prs [pr]))]
      (is (= :reviewing (state/infer-train-status train)))))

  (testing "returns nil when no change needed"
    (let [pr (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr]))]
      (is (nil? (state/infer-train-status train))))))

(deftest auto-transition-train-test
  (testing "auto-transitions train based on PR states"
    (let [pr1 (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
                  (assoc :pr/status :merged))
          pr2 (-> (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
                  (assoc :pr/status :merged))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/status :merging)
                    (assoc :train/prs [pr1 pr2]))
          transitioned (state/auto-transition-train train)]
      (is (= :merged (:train/status transitioned)))))

  (testing "does not transition if not valid"
    (let [train (state/create-train-state (random-uuid) "Test" (random-uuid))
          result (state/auto-transition-train train)]
      (is (= :drafting (:train/status result))))))

;; ============================================================================
;; Recompute train state tests
;; ============================================================================

(deftest recompute-train-state-test
  (testing "recomputes all derived state"
    (let [pr1 (-> (state/create-pr-state "acme/a" 100 "url" "branch" "PR 1" 1)
                  (assoc :pr/status :approved)
                  (assoc :pr/ci-status :passed))
          pr2 (-> (state/create-pr-state "acme/b" 200 "url" "branch" "PR 2" 2)
                  (assoc :pr/depends-on [100])
                  (assoc :pr/status :open))
          train (-> (state/create-train-state (random-uuid) "Test" (random-uuid))
                    (assoc :train/prs [pr1 pr2]))
          recomputed (state/recompute-train-state train)]

      (is (= [100] (:train/ready-to-merge recomputed)))
      (is (empty? (:train/blocking-prs recomputed)))
      (let [progress (:train/progress recomputed)]
        (is (= 2 (:total progress)))
        (is (= 0 (:merged progress)))
        (is (= 1 (:approved progress)))
        (is (= 1 (:pending progress)))))))
