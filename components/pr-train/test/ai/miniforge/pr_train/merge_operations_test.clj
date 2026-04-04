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

(ns ai.miniforge.pr-train.merge-operations-test
  "Tests for ready-to-merge, blocking, merge, and fail-merge operations."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.interface :as train]))

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
