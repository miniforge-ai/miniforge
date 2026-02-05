(ns ai.miniforge.pr-train.interface-test
  "Tests for the pr-train component interface."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.interface :as train]))

;; ============================================================================
;; Manager creation tests
;; ============================================================================

(deftest create-manager-test
  (testing "create-manager returns a manager"
    (let [mgr (train/create-manager)]
      (is (some? mgr))
      (is (satisfies? train/PRTrainManager mgr)))))

;; ============================================================================
;; Train lifecycle tests
;; ============================================================================

(deftest create-train-test
  (testing "create-train returns a train-id"
    (let [mgr (train/create-manager)
          dag-id (random-uuid)
          train-id (train/create-train mgr "Test Train" dag-id "A test train")]
      (is (uuid? train-id))

      (testing "get-train returns the created train"
        (let [t (train/get-train mgr train-id)]
          (is (some? t))
          (is (= train-id (:train/id t)))
          (is (= "Test Train" (:train/name t)))
          (is (= dag-id (:train/dag-id t)))
          (is (= :drafting (:train/status t)))
          (is (empty? (:train/prs t))))))))

(deftest add-pr-test
  (testing "add-pr adds a PR to the train"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)
          updated (train/add-pr mgr train-id "acme/repo" 123
                                "https://github.com/acme/repo/pull/123"
                                "feat/test" "Test PR")]
      (is (some? updated))
      (is (= 1 (count (:train/prs updated))))

      (let [pr (first (:train/prs updated))]
        (is (= "acme/repo" (:pr/repo pr)))
        (is (= 123 (:pr/number pr)))
        (is (= 1 (:pr/merge-order pr)))
        (is (= :draft (:pr/status pr)))
        (is (= :pending (:pr/ci-status pr))))))

  (testing "add-pr assigns sequential merge-order"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 1 "url1" "branch" "PR 1")
      (train/add-pr mgr train-id "acme/b" 2 "url2" "branch" "PR 2")
      (train/add-pr mgr train-id "acme/c" 3 "url3" "branch" "PR 3")

      (let [t (train/get-train mgr train-id)
            orders (map :pr/merge-order (:train/prs t))]
        (is (= [1 2 3] orders))))))

(deftest remove-pr-test
  (testing "remove-pr removes a PR and reorders"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 1 "url1" "branch" "PR 1")
      (train/add-pr mgr train-id "acme/b" 2 "url2" "branch" "PR 2")
      (train/add-pr mgr train-id "acme/c" 3 "url3" "branch" "PR 3")

      (let [updated (train/remove-pr mgr train-id 2)
            remaining (map :pr/number (:train/prs updated))
            orders (map :pr/merge-order (:train/prs updated))]
        (is (= [1 3] remaining))
        (is (= [1 2] orders))))))

(deftest link-prs-test
  (testing "link-prs computes dependencies"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 100 "url1" "branch" "PR 1")
      (train/add-pr mgr train-id "acme/b" 200 "url2" "branch" "PR 2")
      (train/add-pr mgr train-id "acme/c" 300 "url3" "branch" "PR 3")
      (train/link-prs mgr train-id)

      (let [pr1 (train/get-pr-from-train mgr train-id 100)
            pr2 (train/get-pr-from-train mgr train-id 200)
            pr3 (train/get-pr-from-train mgr train-id 300)]
        (is (= [] (:pr/depends-on pr1)))
        (is (= [200 300] (:pr/blocks pr1)))
        (is (= [100] (:pr/depends-on pr2)))
        (is (= [300] (:pr/blocks pr2)))
        (is (= [100 200] (:pr/depends-on pr3)))
        (is (= [] (:pr/blocks pr3)))))))

;; ============================================================================
;; State management tests
;; ============================================================================

(deftest update-pr-status-test
  (testing "update-pr-status transitions PR state"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/repo" 123 "url" "branch" "PR")

      (let [t1 (train/update-pr-status mgr train-id 123 :open)]
        (is (some? t1))
        (is (= :open (:pr/status (train/get-pr-from-train mgr train-id 123)))))

      (let [t2 (train/update-pr-status mgr train-id 123 :reviewing)]
        (is (some? t2))
        (is (= :reviewing (:pr/status (train/get-pr-from-train mgr train-id 123)))))

      (let [t3 (train/update-pr-status mgr train-id 123 :approved)]
        (is (some? t3))
        (is (= :approved (:pr/status (train/get-pr-from-train mgr train-id 123)))))))

  (testing "invalid transitions return nil"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/repo" 123 "url" "branch" "PR")
      (is (nil? (train/update-pr-status mgr train-id 123 :approved)))
      (is (= :draft (:pr/status (train/get-pr-from-train mgr train-id 123)))))))

(deftest update-pr-ci-status-test
  (testing "update-pr-ci-status updates CI status"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/repo" 123 "url" "branch" "PR")

      (train/update-pr-ci-status mgr train-id 123 :running)
      (is (= :running (:pr/ci-status (train/get-pr-from-train mgr train-id 123))))

      (train/update-pr-ci-status mgr train-id 123 :passed)
      (is (= :passed (:pr/ci-status (train/get-pr-from-train mgr train-id 123)))))))

;; ============================================================================
;; Ready-to-merge tests
;; ============================================================================

(deftest ready-to-merge-test
  (testing "PR is ready when deps merged, approved, CI passed, gates passed"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 100 "url1" "branch" "PR 1")
      (train/add-pr mgr train-id "acme/b" 200 "url2" "branch" "PR 2")
      (train/link-prs mgr train-id)

      (is (empty? (train/get-ready-to-merge mgr train-id)))

      (train/update-pr-status mgr train-id 100 :open)
      (train/update-pr-status mgr train-id 100 :reviewing)
      (train/update-pr-status mgr train-id 100 :approved)
      (train/update-pr-ci-status mgr train-id 100 :passed)

      (is (= [100] (train/get-ready-to-merge mgr train-id)))

      (train/update-pr-status mgr train-id 200 :open)
      (train/update-pr-status mgr train-id 200 :reviewing)
      (train/update-pr-status mgr train-id 200 :approved)
      (train/update-pr-ci-status mgr train-id 200 :passed)

      (is (= [100] (train/get-ready-to-merge mgr train-id)))))

  (testing "PR becomes ready after deps merge"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 100 "url1" "branch" "PR 1")
      (train/add-pr mgr train-id "acme/b" 200 "url2" "branch" "PR 2")
      (train/link-prs mgr train-id)

      (train/update-pr-status mgr train-id 100 :open)
      (train/update-pr-status mgr train-id 100 :reviewing)
      (train/update-pr-status mgr train-id 100 :approved)
      (train/update-pr-ci-status mgr train-id 100 :passed)

      (train/update-pr-status mgr train-id 200 :open)
      (train/update-pr-status mgr train-id 200 :reviewing)
      (train/update-pr-status mgr train-id 200 :approved)
      (train/update-pr-ci-status mgr train-id 200 :passed)

      (train/merge-next mgr train-id)
      (train/complete-merge mgr train-id 100)

      (is (= [200] (train/get-ready-to-merge mgr train-id))))))

;; ============================================================================
;; Blocking PR tests
;; ============================================================================

(deftest get-blocking-test
  (testing "blocking PRs are those that could proceed but aren't ready"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 100 "url1" "branch" "PR 1")
      (train/add-pr mgr train-id "acme/b" 200 "url2" "branch" "PR 2")
      (train/link-prs mgr train-id)

      (train/update-pr-status mgr train-id 100 :open)
      (train/update-pr-ci-status mgr train-id 100 :passed)

      (is (= [100] (train/get-blocking mgr train-id)))

      (train/update-pr-status mgr train-id 200 :open)

      (is (= [100] (train/get-blocking mgr train-id))))))

;; ============================================================================
;; Progress tests
;; ============================================================================

(deftest get-progress-test
  (testing "progress tracks PR states"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 100 "url1" "branch" "PR 1")
      (train/add-pr mgr train-id "acme/b" 200 "url2" "branch" "PR 2")
      (train/add-pr mgr train-id "acme/c" 300 "url3" "branch" "PR 3")
      (train/link-prs mgr train-id)

      (let [p (train/get-progress mgr train-id)]
        (is (= 3 (:total p)))
        (is (= 0 (:merged p)))
        (is (= 0 (:approved p)))
        (is (= 3 (:pending p)))
        (is (= 0 (:failed p))))

      (train/update-pr-status mgr train-id 100 :open)
      (train/update-pr-status mgr train-id 100 :reviewing)
      (train/update-pr-status mgr train-id 100 :approved)
      (train/update-pr-ci-status mgr train-id 100 :passed)

      (let [p (train/get-progress mgr train-id)]
        (is (= 1 (:approved p)))
        (is (= 2 (:pending p))))

      (train/merge-next mgr train-id)
      (train/complete-merge mgr train-id 100)

      (let [p (train/get-progress mgr train-id)]
        (is (= 1 (:merged p)))
        (is (= 0 (:approved p)))
        (is (= 2 (:pending p)))))))

;; ============================================================================
;; Merge action tests
;; ============================================================================

(deftest merge-next-test
  (testing "merge-next marks the next ready PR as merging"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 100 "url1" "branch" "PR 1")
      (train/link-prs mgr train-id)

      (is (nil? (train/merge-next mgr train-id)))

      (train/update-pr-status mgr train-id 100 :open)
      (train/update-pr-status mgr train-id 100 :reviewing)
      (train/update-pr-status mgr train-id 100 :approved)
      (train/update-pr-ci-status mgr train-id 100 :passed)

      (let [result (train/merge-next mgr train-id)]
        (is (= 100 (:pr-number result)))
        (is (some? (:train result)))
        (is (= :merging (:pr/status (train/get-pr-from-train mgr train-id 100))))))))

(deftest complete-merge-test
  (testing "complete-merge marks PR as merged"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 100 "url1" "branch" "PR 1")
      (train/link-prs mgr train-id)

      (train/update-pr-status mgr train-id 100 :open)
      (train/update-pr-status mgr train-id 100 :reviewing)
      (train/update-pr-status mgr train-id 100 :approved)
      (train/update-pr-ci-status mgr train-id 100 :passed)
      (train/merge-next mgr train-id)

      (train/complete-merge mgr train-id 100)
      (is (= :merged (:pr/status (train/get-pr-from-train mgr train-id 100)))))))

(deftest fail-merge-test
  (testing "fail-merge marks PR as failed and creates rollback plan"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 100 "url1" "branch" "PR 1")
      (train/link-prs mgr train-id)

      (train/update-pr-status mgr train-id 100 :open)
      (train/update-pr-status mgr train-id 100 :reviewing)
      (train/update-pr-status mgr train-id 100 :approved)
      (train/update-pr-ci-status mgr train-id 100 :passed)
      (train/merge-next mgr train-id)

      (let [updated (train/fail-merge mgr train-id 100 "Merge conflict")]
        (is (= :failed (:pr/status (train/get-pr-from-train mgr train-id 100))))
        (is (some? (:train/rollback-plan updated)))))))

;; ============================================================================
;; Train control tests
;; ============================================================================

(deftest pause-resume-test
  (testing "pause-train and resume-train work"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]

      (let [paused (train/pause-train mgr train-id "Security review")]
        (is (:train/paused? paused))
        (is (= "Security review" (:train/pause-reason paused))))

      (let [resumed (train/resume-train mgr train-id)]
        (is (not (:train/paused? resumed)))
        (is (nil? (:train/pause-reason resumed)))))))

(deftest abandon-train-test
  (testing "abandon-train marks train as abandoned"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)
          abandoned (train/abandon-train mgr train-id "Project cancelled")]
      (is (= :abandoned (:train/status abandoned)))
      (is (= "Project cancelled" (:train/abandon-reason abandoned))))))

;; ============================================================================
;; Evidence tests
;; ============================================================================

(deftest generate-evidence-bundle-test
  (testing "generate-evidence-bundle creates an evidence bundle"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 100 "url1" "branch" "PR 1")
      (train/add-pr mgr train-id "acme/b" 200 "url2" "branch" "PR 2")
      (train/link-prs mgr train-id)

      (train/update-pr-status mgr train-id 100 :open)
      (train/update-pr-status mgr train-id 100 :reviewing)
      (train/update-pr-status mgr train-id 100 :approved)
      (train/update-pr-ci-status mgr train-id 100 :passed)
      (train/merge-next mgr train-id)
      (train/complete-merge mgr train-id 100)

      (let [bundle (train/generate-evidence-bundle mgr train-id)]
        (is (uuid? (:evidence/id bundle)))
        (is (= train-id (:evidence/train-id bundle)))
        (is (= 2 (count (:evidence/prs bundle))))
        (is (= "0.1.0" (:evidence/miniforge-version bundle)))

        (let [summary (:evidence/summary bundle)]
          (is (= 2 (:total-prs summary)))
          (is (= 1 (:human-approvals summary))))))))

(deftest get-evidence-bundle-test
  (testing "get-evidence-bundle retrieves stored bundle"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/a" 100 "url1" "branch" "PR 1")

      (let [bundle (train/generate-evidence-bundle mgr train-id)
            bundle-id (:evidence/id bundle)
            retrieved (train/get-evidence-bundle mgr bundle-id)]
        (is (= bundle retrieved))))))

;; ============================================================================
;; Utility function tests
;; ============================================================================

(deftest list-trains-test
  (testing "list-trains returns all trains"
    (let [mgr (train/create-manager)]
      (train/create-train mgr "Train 1" (random-uuid) nil)
      (train/create-train mgr "Train 2" (random-uuid) nil)

      (let [trains (train/list-trains mgr)]
        (is (= 2 (count trains)))
        (is (every? :train/id trains))
        (is (every? :train/name trains))))))

(deftest find-trains-by-status-test
  (testing "find-trains-by-status filters by status"
    (let [mgr (train/create-manager)
          t1 (train/create-train mgr "Train 1" (random-uuid) nil)
          _t2 (train/create-train mgr "Train 2" (random-uuid) nil)]

      (is (= 2 (count (train/find-trains-by-status mgr :drafting))))

      (train/abandon-train mgr t1 "Cancelled")
      (is (= 1 (count (train/find-trains-by-status mgr :drafting))))
      (is (= 1 (count (train/find-trains-by-status mgr :abandoned)))))))

(deftest train-contains-pr?-test
  (testing "train-contains-pr? checks PR membership"
    (let [mgr (train/create-manager)
          train-id (train/create-train mgr "Test" (random-uuid) nil)]
      (train/add-pr mgr train-id "acme/repo" 123 "url" "branch" "PR")

      (is (train/train-contains-pr? mgr train-id "acme/repo" 123))
      (is (not (train/train-contains-pr? mgr train-id "acme/repo" 456)))
      (is (not (train/train-contains-pr? mgr train-id "other/repo" 123))))))

;; ============================================================================
;; State machine validation tests
;; ============================================================================

(deftest valid-train-transition?-test
  (testing "valid-train-transition? validates transitions"
    (is (train/valid-train-transition? :drafting :open))
    (is (train/valid-train-transition? :open :reviewing))
    (is (train/valid-train-transition? :failed :rolled-back))
    (is (not (train/valid-train-transition? :merged :drafting)))
    (is (not (train/valid-train-transition? :abandoned :open)))))

(deftest valid-pr-transition?-test
  (testing "valid-pr-transition? validates transitions"
    (is (train/valid-pr-transition? :draft :open))
    (is (train/valid-pr-transition? :reviewing :approved))
    (is (train/valid-pr-transition? :merging :merged))
    (is (not (train/valid-pr-transition? :merged :draft)))
    (is (not (train/valid-pr-transition? :draft :merged)))))
