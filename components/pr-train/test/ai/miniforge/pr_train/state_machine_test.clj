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

(ns ai.miniforge.pr-train.state-machine-test
  "Tests for rollback, auto-transition, and recompute."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.state :as state]))

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
          planned (state/compute-rollback-plan train :ci-failure :revert-all)
          plan (:train/rollback-plan planned)]
      (is (= :ci-failure (:trigger plan)))
      (is (= :revert-all (:action plan)))
      (is (= 100 (:checkpoint plan)))
      (is (= [200 100] (:prs-to-revert plan))))))

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
