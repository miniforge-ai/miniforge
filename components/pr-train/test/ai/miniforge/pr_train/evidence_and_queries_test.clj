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

(ns ai.miniforge.pr-train.evidence-and-queries-test
  "Tests for evidence bundles, list, find, contains, and transitions."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.interface :as train]))

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
