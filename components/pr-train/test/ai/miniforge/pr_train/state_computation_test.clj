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

(ns ai.miniforge.pr-train.state-computation-test
  "Tests for ready-to-merge, blocking, progress, and dependency computations."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.state :as state]))

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
          linked (state/link-pr-dependencies train)
          p1 (state/find-pr linked 100)
          p2 (state/find-pr linked 200)
          p3 (state/find-pr linked 300)]
      (is (= [] (:pr/depends-on p1)))
      (is (= [200 300] (:pr/blocks p1)))
      (is (= [100] (:pr/depends-on p2)))
      (is (= [300] (:pr/blocks p2)))
      (is (= [100 200] (:pr/depends-on p3)))
      (is (= [] (:pr/blocks p3))))))
