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

(ns ai.miniforge.pr-train.lifecycle-test
  "Tests for manager, train create, add/remove/link PR."
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
