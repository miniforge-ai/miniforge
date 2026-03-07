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

(ns ai.miniforge.pr-sync.status-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-sync.status :as status]))

;; ─────────────────────────────────────────────────────────────────────────────
;; PR status mapping tests

(deftest pr-status-from-provider-test
  (testing "Open non-draft PR"
    (is (= :open (status/pr-status-from-provider
                   {:state "OPEN" :isDraft false :reviewDecision nil}))))

  (testing "Draft PR"
    (is (= :draft (status/pr-status-from-provider
                    {:state "OPEN" :isDraft true :reviewDecision nil}))))

  (testing "Approved PR"
    (is (= :approved (status/pr-status-from-provider
                       {:state "OPEN" :isDraft false :reviewDecision "APPROVED"}))))

  (testing "Changes requested"
    (is (= :changes-requested (status/pr-status-from-provider
                                {:state "OPEN" :isDraft false :reviewDecision "CHANGES_REQUESTED"}))))

  (testing "Review required"
    (is (= :reviewing (status/pr-status-from-provider
                        {:state "OPEN" :isDraft false :reviewDecision "REVIEW_REQUIRED"}))))

  (testing "Merged PR"
    (is (= :merged (status/pr-status-from-provider
                     {:state "MERGED" :isDraft false :reviewDecision nil})))
    (is (= :merged (status/pr-status-from-provider
                     {:state "CLOSED" :mergedAt "2026-03-06T00:00:00Z"
                      :isDraft false :reviewDecision nil}))))

  (testing "Closed PR"
    (is (= :closed (status/pr-status-from-provider
                     {:state "CLOSED" :isDraft false :reviewDecision nil})))))

(deftest check-rollup->ci-status-test
  (testing "All passed"
    (is (= :passed (status/check-rollup->ci-status
                     [{:conclusion "SUCCESS"} {:conclusion "SUCCESS"}]))))

  (testing "Any failed"
    (is (= :failed (status/check-rollup->ci-status
                     [{:conclusion "SUCCESS"} {:conclusion "FAILURE"}]))))

  (testing "Running checks"
    (is (= :running (status/check-rollup->ci-status
                      [{:conclusion "SUCCESS"} {:status "IN_PROGRESS"}]))))

  (testing "Nil rollup"
    (is (= :pending (status/check-rollup->ci-status nil))))

  (testing "Empty entries"
    (is (= :pending (status/check-rollup->ci-status [])))))

(deftest check-rollup->ci-checks-test
  (testing "Extracts individual check details"
    (let [checks (status/check-rollup->ci-checks
                  [{:name "lint" :conclusion "SUCCESS" :status "COMPLETED"}
                   {:name "test" :conclusion "FAILURE" :status "COMPLETED"}])]
      (is (= 2 (count checks)))
      (is (= "lint" (:name (first checks))))
      (is (= :success (:conclusion (first checks))))
      (is (= :failure (:conclusion (second checks))))))

  (testing "Nil rollup returns empty"
    (is (= [] (status/check-rollup->ci-checks nil))))

  (testing "Uses :context as fallback name"
    (let [checks (status/check-rollup->ci-checks [{:context "ci/build" :conclusion "SUCCESS"}])]
      (is (= "ci/build" (:name (first checks)))))))

(deftest merge-state-status->behind?-test
  (testing "BEHIND is behind"
    (is (true? (status/merge-state-status->behind? "BEHIND"))))
  (testing "DIRTY is behind"
    (is (true? (status/merge-state-status->behind? "DIRTY"))))
  (testing "CLEAN is not behind"
    (is (false? (status/merge-state-status->behind? "CLEAN"))))
  (testing "nil is not behind"
    (is (false? (status/merge-state-status->behind? nil)))))

(deftest provider-pr->train-pr-test
  (testing "Converts provider PR to train PR shape with ci-checks and merge-state"
    (let [pr {:number 42
              :title "Fix auth"
              :url "https://github.com/owner/repo/pull/42"
              :headRefName "fix-auth"
              :state "OPEN"
              :mergedAt nil
              :isDraft false
              :reviewDecision "APPROVED"
              :statusCheckRollup [{:name "lint" :conclusion "SUCCESS"}]
              :mergeStateStatus "CLEAN"}
          result (status/provider-pr->train-pr pr "owner/repo")]
      (is (= 42 (:pr/number result)))
      (is (= "Fix auth" (:pr/title result)))
      (is (= "https://github.com/owner/repo/pull/42" (:pr/url result)))
      (is (= "fix-auth" (:pr/branch result)))
      (is (= :approved (:pr/status result)))
      (is (= :passed (:pr/ci-status result)))
      (is (= "owner/repo" (:pr/repo result)))
      ;; New fields
      (is (= 1 (count (:pr/ci-checks result))))
      (is (= "lint" (:name (first (:pr/ci-checks result)))))
      (is (= "CLEAN" (:pr/merge-state result)))
      (is (false? (:pr/behind-main? result)))))

  (testing "Merged PR retains merged state"
    (let [result (status/provider-pr->train-pr
                  {:number 7
                   :title "Done"
                   :state "CLOSED"
                   :mergedAt "2026-03-06T00:00:00Z"})]
      (is (= :merged (:pr/status result)))
      (is (= "2026-03-06T00:00:00Z" (:pr/merged-at result)))))

  (testing "Behind main PR"
    (let [result (status/provider-pr->train-pr
                  {:number 1 :title "X" :state "OPEN" :mergeStateStatus "BEHIND"})]
      (is (true? (:pr/behind-main? result)))
      (is (= "BEHIND" (:pr/merge-state result)))))

  (testing "Without repo arg, :pr/repo is absent"
    (let [result (status/provider-pr->train-pr {:number 1 :title "X" :state "OPEN"})]
      (is (nil? (:pr/repo result))))))

(deftest gitlab-mr->train-pr-test
  (testing "Converts GitLab MR to train PR shape"
    (let [mr {:iid 34
              :title "Fix GL"
              :web_url "https://gitlab.com/team/service/-/merge_requests/34"
              :source_branch "fix-gl"
              :state "opened"
              :draft false
              :merge_status "can_be_merged"
              :head_pipeline {:status "success"}}
          result (status/gitlab-mr->train-pr mr "gitlab:team/service")]
      (is (= 34 (:pr/number result)))
      (is (= "Fix GL" (:pr/title result)))
      (is (= :merge-ready (:pr/status result)))
      (is (= :passed (:pr/ci-status result)))
      (is (= "gitlab:team/service" (:pr/repo result)))))

  (testing "Draft MR"
    (let [result (status/gitlab-mr->train-pr {:iid 1 :title "Draft: WIP" :state "opened"
                                              :draft true :source_branch "wip"
                                              :web_url "https://gl.com/x/y/-/merge_requests/1"}
                                             "gitlab:x/y")]
      (is (= :draft (:pr/status result))))))
