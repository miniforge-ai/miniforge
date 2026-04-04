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

(ns ai.miniforge.pr-train.state-creation-test
  "Tests for train and PR state creation and CRUD."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.state :as state]))

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
