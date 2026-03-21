(ns ai.miniforge.pr-train.train-control-test
  "Tests for pause, resume, abandon, and progress."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.interface :as train]))

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
