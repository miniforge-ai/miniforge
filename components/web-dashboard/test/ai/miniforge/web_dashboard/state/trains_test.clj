;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.state.trains-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.web-dashboard.state.core :as core]
   [ai.miniforge.web-dashboard.state.trains :as sut]))

(defn temp-config-path
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "fleet-config"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit dir)
    (str (.getAbsolutePath dir) "/config.edn")))

;; ============================================================================
;; Layer 0: Helper and utility function tests
;; ============================================================================

(deftest normalized-limit-test
  (testing "Returns the integer when positive"
    (is (= 10 (sut/normalized-limit 10 50))))
  (testing "Falls back to default for non-integer"
    (is (= 50 (sut/normalized-limit "abc" 50)))
    (is (= 50 (sut/normalized-limit nil 50))))
  (testing "Falls back to default for zero or negative"
    (is (= 50 (sut/normalized-limit 0 50)))
    (is (= 50 (sut/normalized-limit -5 50)))))

(deftest ensure-message-test
  (testing "Returns message when present and non-blank"
    (is (= "hello" (sut/ensure-message "hello" "fallback"))))
  (testing "Returns fallback for nil"
    (is (= "fallback" (sut/ensure-message nil "fallback"))))
  (testing "Returns fallback for blank string"
    (is (= "fallback" (sut/ensure-message "  " "fallback"))))
  (testing "Trims the message"
    (is (= "hello" (sut/ensure-message "  hello  " "fallback")))))

(deftest normalize-repo-slug-test
  (testing "Normalizes case and trims whitespace"
    (is (= "acme/service" (sut/normalize-repo-slug "  Acme/Service  "))))
  (testing "Handles already-normalized slugs"
    (is (= "acme/service" (sut/normalize-repo-slug "acme/service")))))

(deftest valid-repo-slug-test
  (testing "Valid slugs"
    (is (true? (sut/valid-repo-slug? "acme/service")))
    (is (true? (sut/valid-repo-slug? "my-org/my-repo")))
    (is (true? (sut/valid-repo-slug? "org_1/repo.name"))))
  (testing "Invalid slugs"
    (is (false? (sut/valid-repo-slug? "noslash")))
    (is (false? (sut/valid-repo-slug? "too/many/slashes")))
    (is (false? (sut/valid-repo-slug? "")))
    (is (false? (sut/valid-repo-slug? "has spaces/repo")))))

(deftest result-success-test
  (testing "result-success sets success flags"
    (let [r (sut/result-success {:foo :bar})]
      (is (true? (:success? r)))
      (is (true? (:success r)))
      (is (= :bar (:foo r))))))

(deftest result-failure-test
  (testing "result-failure sets failure flags with message"
    (let [r (sut/result-failure "bad thing")]
      (is (false? (:success? r)))
      (is (false? (:success r)))
      (is (= "bad thing" (:error r)))
      (is (some? (:anomaly r)))))
  (testing "result-failure with nil message uses fallback"
    (let [r (sut/result-failure nil)]
      (is (false? (:success? r)))
      (is (string? (:error r)))
      (is (not (str/blank? (:error r)))))))

(deftest result-exception-test
  (testing "result-exception captures exception info"
    (let [ex (ex-info "boom" {:detail 1})
          r (sut/result-exception "wrapped" ex)]
      (is (false? (:success? r)))
      (is (= "wrapped" (:error r)))
      (is (string? (:exception r)))
      (is (some? (:anomaly r))))))

(deftest succeeded?-test
  (testing "Returns true for success maps"
    (is (true? (sut/succeeded? {:success? true}))))
  (testing "Returns false for failure maps"
    (is (false? (sut/succeeded? {:success? false}))))
  (testing "Returns false for nil/missing key"
    (is (false? (sut/succeeded? {})))
    (is (false? (sut/succeeded? nil)))))

;; ============================================================================
;; Layer 0: Config management tests
;; ============================================================================

(deftest add-configured-repo-validates-and-dedupes-test
  (let [config-path (temp-config-path)
        state (atom {})]
    (with-redefs-fn {#'sut/default-fleet-config-path config-path}
      (fn []
        (let [invalid (sut/add-configured-repo! state "not-a-repo-slug")]
          (is (false? (:success? invalid)))
          (is (= :anomalies/fault (get-in invalid [:anomaly :anomaly/category]))))

        (let [first-add (sut/add-configured-repo! state "Acme/Service")
              second-add (sut/add-configured-repo! state "acme/service")]
          (is (true? (:success? first-add)))
          (is (true? (:added? first-add)))
          (is (= "acme/service" (:repo first-add)))
          (is (true? (:success? second-add)))
          (is (false? (:added? second-add)))
          (is (= ["acme/service"] (sut/get-configured-repos state))))))))

(deftest add-configured-repo-blank-test
  (let [config-path (temp-config-path)
        state (atom {})]
    (with-redefs-fn {#'sut/default-fleet-config-path config-path}
      (fn []
        (let [r (sut/add-configured-repo! state "  ")]
          (is (false? (:success? r)))
          (is (clojure.string/includes? (:error r) "required")))))))

(deftest get-configured-repos-filters-invalid-test
  (let [config-path (temp-config-path)
        state (atom {})]
    (with-redefs-fn {#'sut/default-fleet-config-path config-path}
      (fn []
        ;; Write config with a mix of valid and invalid slugs
        (spit config-path (pr-str {:fleet {:repos ["acme/good" "bad" "org/ok" "a/b/c"]}}))
        (is (= ["acme/good" "org/ok"] (sut/get-configured-repos state)))))))

(deftest get-configured-repos-missing-config-test
  (let [state (atom {})]
    (with-redefs-fn {#'sut/default-fleet-config-path "/nonexistent/path/config.edn"}
      (fn []
        (is (= [] (sut/get-configured-repos state)))))))

;; ============================================================================
;; Layer 0: Provider PR field parsing
;; ============================================================================

(deftest pr-status-from-provider-test
  (testing "Closed state"
    (is (= :closed (sut/pr-status-from-provider {:state "CLOSED"})))
    (is (= :closed (sut/pr-status-from-provider {:state "MERGED"}))))
  (testing "Draft"
    (is (= :draft (sut/pr-status-from-provider {:state "OPEN" :isDraft true}))))
  (testing "Approved"
    (is (= :approved (sut/pr-status-from-provider {:state "OPEN" :isDraft false :reviewDecision "APPROVED"}))))
  (testing "Changes requested"
    (is (= :changes-requested (sut/pr-status-from-provider {:state "OPEN" :isDraft false :reviewDecision "CHANGES_REQUESTED"}))))
  (testing "Review required"
    (is (= :reviewing (sut/pr-status-from-provider {:state "OPEN" :isDraft false :reviewDecision "REVIEW_REQUIRED"}))))
  (testing "Open with no review decision"
    (is (= :open (sut/pr-status-from-provider {:state "OPEN" :isDraft false})))))

(deftest check-rollup->ci-status-test
  (testing "Nil rollup returns :pending"
    (is (= :pending (sut/check-rollup->ci-status nil))))
  (testing "Empty list returns :pending"
    (is (= :pending (sut/check-rollup->ci-status []))))
  (testing "All SUCCESS conclusions return :passed"
    (is (= :passed (sut/check-rollup->ci-status [{:conclusion "SUCCESS"} {:conclusion "NEUTRAL"}]))))
  (testing "Any FAILURE conclusion returns :failed"
    (is (= :failed (sut/check-rollup->ci-status [{:conclusion "SUCCESS"} {:conclusion "FAILURE"}]))))
  (testing "TIMED_OUT conclusion returns :failed"
    (is (= :failed (sut/check-rollup->ci-status [{:conclusion "TIMED_OUT"}]))))
  (testing "IN_PROGRESS status returns :running"
    (is (= :running (sut/check-rollup->ci-status [{:status "IN_PROGRESS"}]))))
  (testing "PENDING status returns :running"
    (is (= :running (sut/check-rollup->ci-status [{:status "PENDING"}]))))
  (testing "QUEUED status returns :running"
    (is (= :running (sut/check-rollup->ci-status [{:status "QUEUED"}]))))
  (testing "Single non-sequential rollup treated as single-element list"
    (is (= :failed (sut/check-rollup->ci-status {:conclusion "FAILURE"})))))

(deftest merge-state-status->behind-test
  (testing "BEHIND is behind"
    (is (true? (sut/merge-state-status->behind? "BEHIND"))))
  (testing "DIRTY is behind"
    (is (true? (sut/merge-state-status->behind? "DIRTY"))))
  (testing "CLEAN is not behind"
    (is (false? (sut/merge-state-status->behind? "CLEAN"))))
  (testing "nil is not behind"
    (is (false? (sut/merge-state-status->behind? nil)))))

(deftest provider-pr->train-pr-test
  (testing "Maps all provider fields to train PR map"
    (let [pr {:number 42
              :title "My PR"
              :url "https://github.com/acme/service/pull/42"
              :headRefName "feature/x"
              :state "OPEN"
              :isDraft false
              :reviewDecision "APPROVED"
              :statusCheckRollup [{:conclusion "SUCCESS"}]
              :mergeStateStatus "CLEAN"}
          result (sut/provider-pr->train-pr pr)]
      (is (= 42 (:pr/number result)))
      (is (= "My PR" (:pr/title result)))
      (is (= "feature/x" (:pr/branch result)))
      (is (= :approved (:pr/status result)))
      (is (= :passed (:pr/ci-status result)))
      (is (false? (:pr/behind-main? result))))))

(deftest fetch-open-prs-parses-provider-fields-test
  (with-redefs-fn {#'sut/run-gh
                   (fn [& _args]
                     {:success? true
                      :out (json/generate-string
                            [{:number 40
                              :title "Second PR"
                              :url "https://github.com/acme/service/pull/40"
                              :state "OPEN"
                              :headRefName "feature/two"
                              :isDraft true
                              :reviewDecision "REVIEW_REQUIRED"
                              :statusCheckRollup [{:status "IN_PROGRESS"}]}
                             {:number 12
                              :title "First PR"
                              :url "https://github.com/acme/service/pull/12"
                              :state "OPEN"
                              :headRefName "feature/one"
                              :isDraft false
                              :reviewDecision "APPROVED"
                              :statusCheckRollup [{:status "COMPLETED"
                                                   :conclusion "SUCCESS"}]}])
                      :err ""})}
    (fn []
      (let [result (sut/fetch-open-prs "acme/service")
            prs (:prs result)
            by-number (into {} (map (juxt :pr/number identity) prs))]
        (is (true? (:success? result)))
        (is (= [12 40] (mapv :pr/number prs)))
        (is (= :approved (get-in by-number [12 :pr/status])))
        (is (= :passed (get-in by-number [12 :pr/ci-status])))
        (is (= :draft (get-in by-number [40 :pr/status])))
        (is (= :running (get-in by-number [40 :pr/ci-status])))))))

(deftest fetch-open-prs-failure-test
  (testing "Provider failure returns error with actionable hint"
    (with-redefs-fn {#'sut/run-gh
                     (fn [& _args]
                       {:success? false :out "" :err "not logged in to any GitHub hosts"})}
      (fn []
        (let [result (sut/fetch-open-prs "acme/service")]
          (is (false? (:success? result)))
          (is (= "acme/service" (:repo result)))
          (is (string? (:action result))))))))

(deftest fetch-open-prs-malformed-json-test
  (testing "Malformed JSON from provider returns parse error"
    (with-redefs-fn {#'sut/run-gh
                     (fn [& _args]
                       {:success? true :out "not json" :err ""})}
      (fn []
        (let [result (sut/fetch-open-prs "acme/service")]
          (is (false? (:success? result)))
          (is (string? (:error result))))))))

;; ============================================================================
;; Layer 1: Deterministic train enrichment tests
;; ============================================================================

(deftest gates-passed-test
  (testing "No gates means passed"
    (is (true? (sut/gates-passed? {}))))
  (testing "All gates passed"
    (is (true? (sut/gates-passed? {:pr/gate-results [{:gate/passed? true} {:gate/passed? true}]}))))
  (testing "One gate failed"
    (is (false? (sut/gates-passed? {:pr/gate-results [{:gate/passed? true} {:gate/passed? false}]})))))

(deftest gates-score-test
  (testing "Empty gates => 1.0"
    (is (= 1.0 (sut/gates-score {}))))
  (testing "All passed => 1.0"
    (is (= 1.0 (sut/gates-score {:pr/gate-results [{:gate/passed? true}]}))))
  (testing "Half passed => 0.5"
    (is (= 0.5 (sut/gates-score {:pr/gate-results [{:gate/passed? true} {:gate/passed? false}]})))))

(deftest pr-sort-key-test
  (testing "Uses merge-order then number"
    (is (= [1 42] (sut/pr-sort-key {:pr/merge-order 1 :pr/number 42}))))
  (testing "Missing merge-order uses MAX_VALUE"
    (is (= [Long/MAX_VALUE 10] (sut/pr-sort-key {:pr/number 10})))))

(deftest sort-prs-test
  (testing "PRs sorted by merge-order then number"
    (let [prs [{:pr/number 3 :pr/merge-order 2}
               {:pr/number 1 :pr/merge-order 1}
               {:pr/number 2 :pr/merge-order 1}]
          sorted (sut/sort-prs prs)]
      (is (= [1 2 3] (mapv :pr/number sorted))))))

(deftest unresolved-deps-test
  (testing "No deps returns empty"
    (let [pm {1 {:pr/number 1 :pr/status :open}}]
      (is (= [] (sut/unresolved-deps pm (get pm 1))))))
  (testing "Merged dep is resolved"
    (let [pm {1 {:pr/number 1 :pr/status :merged}
              2 {:pr/number 2 :pr/status :open :pr/depends-on [1]}}]
      (is (= [] (sut/unresolved-deps pm (get pm 2))))))
  (testing "Open dep is unresolved"
    (let [pm {1 {:pr/number 1 :pr/status :open}
              2 {:pr/number 2 :pr/status :open :pr/depends-on [1]}}]
      (is (= [1] (sut/unresolved-deps pm (get pm 2))))))
  (testing "Multiple deps, some resolved"
    (let [pm {1 {:pr/number 1 :pr/status :merged}
              3 {:pr/number 3 :pr/status :open}
              5 {:pr/number 5 :pr/status :open :pr/depends-on [1 3]}}]
      (is (= [3] (sut/unresolved-deps pm (get pm 5)))))))

(deftest deps-score-test
  (testing "No deps => 1.0"
    (let [pm {1 {:pr/number 1 :pr/status :open}}]
      (is (= 1.0 (sut/deps-score pm (get pm 1))))))
  (testing "All deps merged => 1.0"
    (let [pm {1 {:pr/number 1 :pr/status :merged}
              2 {:pr/number 2 :pr/status :open :pr/depends-on [1]}}]
      (is (= 1.0 (sut/deps-score pm (get pm 2))))))
  (testing "No deps merged => 0.0"
    (let [pm {1 {:pr/number 1 :pr/status :open}
              2 {:pr/number 2 :pr/status :open :pr/depends-on [1]}}]
      (is (= 0.0 (sut/deps-score pm (get pm 2)))))))

;; ============================================================================
;; Layer 1: Readiness state tests
;; ============================================================================

(deftest readiness-state-deterministic-test
  (testing "readiness-state returns deterministic keyword for known inputs"
    (let [pr-map {1 {:pr/number 1 :pr/status :merged}
                  2 {:pr/number 2 :pr/status :approved :pr/ci-status :passed
                     :pr/depends-on [1] :pr/behind-main? false}}
          merged-pr (get pr-map 1)
          ready-pr (get pr-map 2)]
      (is (= :merge-ready (sut/readiness-state pr-map merged-pr)))
      (is (= :merge-ready (sut/readiness-state pr-map ready-pr))))))

(deftest readiness-state-dep-blocked-test
  (testing "PR with unmerged dep is :dep-blocked"
    (let [pr-map {1 {:pr/number 1 :pr/status :open}
                  2 {:pr/number 2 :pr/status :approved :pr/ci-status :passed
                     :pr/depends-on [1] :pr/behind-main? false}}]
      (is (= :dep-blocked (sut/readiness-state pr-map (get pr-map 2)))))))

(deftest readiness-state-ci-failing-test
  (testing "PR with failed CI is :ci-failing"
    (let [pr-map {1 {:pr/number 1 :pr/status :approved :pr/ci-status :failed
                     :pr/behind-main? false}}]
      (is (= :ci-failing (sut/readiness-state pr-map (get pr-map 1)))))))

(deftest readiness-state-merge-conflicts-test
  (testing "PR behind main is :merge-conflicts"
    (let [pr-map {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed
                     :pr/behind-main? true}}]
      (is (= :merge-conflicts (sut/readiness-state pr-map (get pr-map 1)))))))

(deftest readiness-state-changes-requested-test
  (testing "PR with changes-requested is :changes-requested"
    (let [pr-map {1 {:pr/number 1 :pr/status :changes-requested :pr/ci-status :passed
                     :pr/behind-main? false}}]
      (is (= :changes-requested (sut/readiness-state pr-map (get pr-map 1)))))))

(deftest readiness-state-policy-failing-test
  (testing "PR with failed gates is :policy-failing"
    (let [pr-map {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed
                     :pr/behind-main? false
                     :pr/gate-results [{:gate/passed? false}]}}]
      (is (= :policy-failing (sut/readiness-state pr-map (get pr-map 1)))))))

(deftest readiness-state-needs-review-test
  (testing "Open PR with no blockers is :needs-review"
    (let [pr-map {1 {:pr/number 1 :pr/status :open :pr/ci-status :passed
                     :pr/behind-main? false}}]
      (is (= :needs-review (sut/readiness-state pr-map (get pr-map 1))))))
  (testing "Draft PR is :needs-review"
    (let [pr-map {1 {:pr/number 1 :pr/status :draft :pr/ci-status :passed
                     :pr/behind-main? false}}]
      (is (= :needs-review (sut/readiness-state pr-map (get pr-map 1))))))
  (testing "Reviewing PR is :needs-review"
    (let [pr-map {1 {:pr/number 1 :pr/status :reviewing :pr/ci-status :passed
                     :pr/behind-main? false}}]
      (is (= :needs-review (sut/readiness-state pr-map (get pr-map 1)))))))

;; ============================================================================
;; Layer 1: Blockers tests
;; ============================================================================

(deftest blockers-deterministic-sorted-test
  (testing "Blockers are sorted by type rank, then message — deterministic output"
    (let [pr-map {1 {:pr/number 1 :pr/status :open}}
          pr {:pr/number 2
              :pr/status :open
              :pr/ci-status :failed
              :pr/depends-on [1]
              :pr/behind-main? true
              :pr/gate-results [{:gate/passed? false}]}
          result (sut/blockers pr-map pr)
          types (mapv :blocker/type result)]
      ;; dependency < review < ci < policy < conflict
      (is (= [:dependency :review :ci :policy :conflict] types))
      ;; Messages are strings
      (is (every? string? (map :blocker/message result))))))

(deftest blockers-empty-for-ready-pr-test
  (testing "Merge-ready PR has no blockers"
    (let [pr-map {1 {:pr/number 1 :pr/status :merged}
                  2 {:pr/number 2 :pr/status :approved :pr/ci-status :passed
                     :pr/depends-on [1] :pr/behind-main? false}}
          result (sut/blockers pr-map (get pr-map 2))]
      (is (empty? result)))))

(deftest blockers-draft-pr-test
  (testing "Draft PR has review blocker"
    (let [pr-map {1 {:pr/number 1 :pr/status :draft :pr/ci-status :passed
                     :pr/behind-main? false}}
          result (sut/blockers pr-map (get pr-map 1))]
      (is (= [:review] (mapv :blocker/type result)))
      (is (clojure.string/includes? (:blocker/message (first result)) "draft")))))

(deftest blockers-reviewing-pr-test
  (testing "Reviewing PR has review blocker about approval"
    (let [pr-map {1 {:pr/number 1 :pr/status :reviewing :pr/ci-status :passed
                     :pr/behind-main? false}}
          result (sut/blockers pr-map (get pr-map 1))]
      (is (some #(= :review (:blocker/type %)) result))
      (is (some #(clojure.string/includes? (:blocker/message %) "approval") result)))))

(deftest blockers-running-ci-test
  (testing "Running CI adds ci blocker"
    (let [pr-map {1 {:pr/number 1 :pr/status :approved :pr/ci-status :running
                     :pr/behind-main? false}}
          result (sut/blockers pr-map (get pr-map 1))]
      (is (some #(= :ci (:blocker/type %)) result))
      (is (some #(clojure.string/includes? (:blocker/message %) "running") result)))))

(deftest blockers-multiple-failed-gates-test
  (testing "Multiple failed gates produce single policy blocker with count"
    (let [pr-map {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed
                     :pr/behind-main? false
                     :pr/gate-results [{:gate/passed? false}
                                       {:gate/passed? false}
                                       {:gate/passed? true}]}}
          result (sut/blockers pr-map (get pr-map 1))
          policy-blockers (filter #(= :policy (:blocker/type %)) result)]
      (is (= 1 (count policy-blockers)))
      (is (clojure.string/includes? (:blocker/message (first policy-blockers)) "2")))))

(deftest blockers-source-field-test
  (testing "Each blocker has a :blocker/source"
    (let [pr-map {1 {:pr/number 1 :pr/status :open :pr/ci-status :failed
                     :pr/behind-main? true}}
          result (sut/blockers pr-map (get pr-map 1))]
      (is (every? #(string? (:blocker/source %)) result)))))

;; ============================================================================
;; Layer 1: Readiness composite tests
;; ============================================================================

(deftest readiness-complete-test
  (testing "readiness returns all expected keys"
    (let [pr-map {1 {:pr/number 1 :pr/status :merged}}
          pr {:pr/number 2 :pr/status :approved :pr/ci-status :passed
              :pr/depends-on [1] :pr/behind-main? false}
          result (sut/readiness pr-map pr)]
      (is (contains? result :readiness/state))
      (is (contains? result :readiness/score))
      (is (contains? result :readiness/threshold))
      (is (contains? result :readiness/ready?))
      (is (contains? result :readiness/factors))
      (is (contains? result :readiness/blockers))
      (is (= 5 (count (:readiness/factors result))))
      (is (double? (:readiness/score result))))))

(deftest readiness-score-range-test
  (testing "Readiness score is between 0.0 and 1.0"
    (let [test-prs [{:pr/number 1 :pr/status :open :pr/ci-status :failed :pr/behind-main? true}
                    {:pr/number 2 :pr/status :approved :pr/ci-status :passed :pr/behind-main? false}
                    {:pr/number 3 :pr/status :draft :pr/ci-status :pending :pr/behind-main? false}]
          pr-map (into {} (map (juxt :pr/number identity) test-prs))]
      (doseq [pr test-prs]
        (let [score (:readiness/score (sut/readiness pr-map pr))]
          (is (<= 0.0 score 1.0) (str "Score out of range for PR#" (:pr/number pr))))))))

(deftest readiness-factors-sum-to-score-test
  (testing "Sum of factor contributions equals readiness score"
    (let [pr-map {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed :pr/behind-main? false}}
          r (sut/readiness pr-map (get pr-map 1))
          computed-sum (reduce + 0.0 (map :contribution (:readiness/factors r)))]
      (is (< (Math/abs (- computed-sum (:readiness/score r))) 0.001)))))

(deftest readiness-ready-requires-merge-ready-state-test
  (testing "ready? is false even with high score if not :merge-ready"
    (let [pr-map {1 {:pr/number 1 :pr/status :open :pr/ci-status :passed :pr/behind-main? false}}
          r (sut/readiness pr-map (get pr-map 1))]
      (is (false? (:readiness/ready? r))))))

(deftest readiness-factor-weights-sum-to-one-test
  (testing "All factor weights sum to 1.0"
    (let [pr-map {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed :pr/behind-main? false}}
          r (sut/readiness pr-map (get pr-map 1))
          weight-sum (reduce + 0.0 (map :weight (:readiness/factors r)))]
      (is (< (Math/abs (- weight-sum 1.0)) 0.001)))))

;; ============================================================================
;; Layer 1: Train enrichment tests
;; ============================================================================

(deftest enrich-train-adds-readiness-and-blocking-test
  (testing "enrich-train annotates PRs with readiness and blocking details"
    (let [train {:train/prs [{:pr/number 1 :pr/status :approved :pr/ci-status :passed
                              :pr/merge-order 1 :pr/behind-main? false}
                             {:pr/number 2 :pr/status :open :pr/ci-status :failed
                              :pr/merge-order 2 :pr/behind-main? false}]
                 :train/ready-to-merge [1]
                 :train/blocking-prs [2]}
          enriched (sut/enrich-train train)]
      ;; Each PR has readiness
      (is (every? :pr/readiness (:train/prs enriched)))
      ;; Each PR has blocking-reasons vector
      (is (every? #(vector? (:pr/blocking-reasons %)) (:train/prs enriched)))
      ;; Blocking details present
      (is (vector? (:train/blocking-details enriched)))
      ;; Readiness summary present
      (is (map? (:train/readiness-summary enriched))))))

;; ============================================================================
;; Error classification tests
;; ============================================================================

;; TODO: classify-sync-error tests commented out — function not yet implemented.
;; Uncomment when classify-sync-error is added to trains.clj.
(comment

  (deftest classify-sync-error-auth-test
    (testing "Auth errors are classified deterministically"
      (is (= :auth (:error/category (sut/classify-sync-error "authentication required"))))
      (is (= :auth (:error/category (sut/classify-sync-error "not logged in to any GitHub hosts"))))
      (is (string? (:error/action (sut/classify-sync-error "auth failure"))))))

  (deftest classify-sync-error-access-test
    (testing "Access/permission errors"
      (is (= :access (:error/category (sut/classify-sync-error "repository not found"))))
      (is (= :access (:error/category (sut/classify-sync-error "403 Forbidden"))))
      (is (= :access (:error/category (sut/classify-sync-error "permission denied"))))
      (is (= :access (:error/category (sut/classify-sync-error "access denied for repo"))))))

  (deftest classify-sync-error-rate-limit-test
    (testing "Rate limit errors"
      (is (= :rate-limit (:error/category (sut/classify-sync-error "API rate limit exceeded"))))
      (is (= :rate-limit (:error/category (sut/classify-sync-error "secondary rate limit hit"))))))

  (deftest classify-sync-error-parse-test
    (testing "Parse errors"
      (is (= :parse (:error/category (sut/classify-sync-error "failed to parse JSON"))))
      (is (= :parse (:error/category (sut/classify-sync-error "malformed response body"))))
      (is (= :parse (:error/category (sut/classify-sync-error "invalid json token"))))))

  (deftest classify-sync-error-network-test
    (testing "Network/timeout errors"
      (is (= :network (:error/category (sut/classify-sync-error "connection timed out"))))
      (is (= :network (:error/category (sut/classify-sync-error "network unreachable"))))
      (is (= :network (:error/category (sut/classify-sync-error "connection refused"))))
      (is (= :network (:error/category (sut/classify-sync-error "request timeout"))))))

  (deftest classify-sync-error-unknown-test
    (testing "Unknown errors get classified with a generic action"
      (let [result (sut/classify-sync-error "some weird error we haven't seen")]
        (is (= :unknown (:error/category result)))
        (is (string? (:error/action result))))))

  (deftest classify-sync-error-nil-test
    (testing "Nil input returns unknown gracefully"
      (is (= :unknown (:error/category (sut/classify-sync-error nil))))))

  (deftest classify-sync-error-empty-string-test
    (testing "Empty string returns unknown"
      (is (= :unknown (:error/category (sut/classify-sync-error ""))))))

  (deftest classify-sync-error-case-insensitive-test
    (testing "Classification is case-insensitive"
      (is (= :auth (:error/category (sut/classify-sync-error "AUTHENTICATION REQUIRED"))))
      (is (= :network (:error/category (sut/classify-sync-error "CONNECTION TIMED OUT"))))))) ;; end comment — classify-sync-error

(deftest with-actionable-error-test
  (testing "Adds action to failure result"
    (let [r (sut/with-actionable-error {:success? false :error "authentication required"})]
      (is (string? (:action r)))))
  (testing "Leaves success result unchanged"
    (let [r (sut/with-actionable-error {:success? true})]
      (is (nil? (:action r))))))

;; ============================================================================
;; Layer 2: Train/DAG state access tests
;; ============================================================================

(deftest get-train-detail-invalid-id-test
  (testing "Invalid train id returns error"
    (let [state (atom {:pr-train-manager :mgr})]
      (is (= {:error "Invalid train id."}
             (sut/get-train-detail state "not-a-uuid"))))))

(deftest get-train-detail-no-manager-test
  (testing "No pr-train-manager returns error"
    (let [state (atom {})]
      (is (= {:error "PR train manager not available"}
             (sut/get-train-detail state (str (random-uuid))))))))

(deftest get-train-detail-not-found-test
  (testing "Valid UUID but train not found returns error"
    (let [state (atom {:pr-train-manager :mgr})
          tid (str (random-uuid))]
      (with-redefs-fn {#'core/safe-call (fn [_ _ & _] nil)}
        (fn []
          (is (= {:error "Train not found"}
                 (sut/get-train-detail state tid))))))))

(deftest train-action-no-manager-test
  (testing "No manager returns nil"
    (let [state (atom {})]
      (is (nil? (sut/train-action! state (str (random-uuid)) "pause"))))))

(deftest train-action-unknown-action-test
  (testing "Unknown action returns nil"
    (let [state (atom {:pr-train-manager :mgr})]
      (with-redefs-fn {#'core/safe-call (fn [_ _ & _] nil)}
        (fn []
          (is (nil? (sut/train-action! state (str (random-uuid)) "unknown"))))))))

;; ============================================================================
;; Layer 3: Sync status rendering tests
;; ============================================================================

(deftest sync-status-success-test
  (testing "Fully successful sync produces :success status"
    (let [result {:success? true :success true
                  :synced 3 :failed 0
                  :repos ["a/b" "c/d" "e/f"]
                  :results [{:success? true :repo "a/b"}
                            {:success? true :repo "c/d"}
                            {:success? true :repo "e/f"}]
                  :summary {:added-prs 5 :removed-prs 1 :tracked-prs 10}}
          ss (sut/sync-status result)]
      (is (= :success (:status ss)))
      (is (= 3 (:synced ss)))
      (is (= 0 (:failed ss)))
      (is (empty? (:failures ss)))
      (is (inst? (:timestamp ss))))))

(deftest sync-status-partial-test
  (testing "Partial sync produces :partial status with classified failures"
    (let [result {:success? false :success false
                  :synced 2 :failed 1
                  :repos ["a/b" "c/d" "e/f"]
                  :results [{:success? true :repo "a/b"}
                            {:success? true :repo "c/d"}
                            {:success? false :repo "e/f"
                             :error "authentication required"}]
                  :summary {:added-prs 3 :removed-prs 0 :tracked-prs 7}}
          ss (sut/sync-status result)]
      (is (= :partial (:status ss)))
      (is (= 2 (:synced ss)))
      (is (= 1 (:failed ss)))
      (is (= 1 (count (:failures ss))))
      (is (= :auth (:error-category (first (:failures ss)))))
      (is (string? (:action (first (:failures ss))))))))

(deftest sync-status-total-failure-test
  (testing "Total failure produces :failed status"
    (let [result {:success? false :success false
                  :synced 0 :failed 2
                  :repos ["a/b" "c/d"]
                  :results [{:success? false :repo "a/b" :error "not found"}
                            {:success? false :repo "c/d" :error "rate limit exceeded"}]
                  :summary {:added-prs 0 :removed-prs 0 :tracked-prs 0}}
          ss (sut/sync-status result)]
      (is (= :failed (:status ss)))
      (is (= 0 (:synced ss)))
      (is (= 2 (:failed ss)))
      ;; Failures sorted by repo
      (is (= ["a/b" "c/d"] (mapv :repo (:failures ss))))
      ;; Error categories assigned
      (is (= :access (:error-category (first (:failures ss)))))
      (is (= :rate-limit (:error-category (second (:failures ss))))))))

(deftest sync-status-no-results-test
  (testing "Result with no results key still works"
    (let [result {:success? true :synced 0 :failed 0}
          ss (sut/sync-status result)]
      (is (= :success (:status ss)))
      (is (empty? (:failures ss))))))

;; ============================================================================
;; Layer 3: Sync plan tests
;; ============================================================================

(deftest train-sync-plan-test
  (testing "Identifies PRs to add, remove, and status map"
    (let [before-train {:train/prs [{:pr/number 1} {:pr/number 2}]}
          prs [{:pr/number 2 :pr/status :open :pr/ci-status :passed}
               {:pr/number 3 :pr/status :open :pr/ci-status :pending}]
          plan (sut/train-sync-plan before-train prs)]
      ;; PR#3 is new
      (is (= [3] (mapv :pr/number (:to-add plan))))
      ;; PR#1 was removed
      (is (= [1] (:to-remove plan)))
      ;; Status map has current PR statuses
      (is (= {2 {:pr/status :open :pr/ci-status :passed}
              3 {:pr/status :open :pr/ci-status :pending}}
             (:status-map plan))))))

(deftest train-sync-plan-no-changes-test
  (testing "No changes when PRs are the same"
    (let [before-train {:train/prs [{:pr/number 1}]}
          prs [{:pr/number 1 :pr/status :open :pr/ci-status :passed}]
          plan (sut/train-sync-plan before-train prs)]
      (is (empty? (:to-add plan)))
      (is (empty? (:to-remove plan))))))

(deftest apply-sync-plan-orders-mutations-test
  (let [calls (atom [])
        train-id (random-uuid)
        plan {:to-add [{:pr/number 101
                        :pr/url "https://github.com/acme/service/pull/101"
                        :pr/branch "feature/a"
                        :pr/title "A"}]
              :to-remove [99]
              :status-map {101 {:pr/status :open :pr/ci-status :pending}}}]
    (with-redefs-fn {#'core/safe-call
                     (fn [_ns-sym fn-sym & _args]
                       (swap! calls conj fn-sym)
                       nil)}
      (fn []
        (sut/apply-sync-plan! :manager train-id "acme/service" plan)
        (is (= ['add-pr 'remove-pr 'sync-pr-status 'link-prs]
               @calls))))))

;; ============================================================================
;; Layer 3: Fleet sync integration tests
;; ============================================================================

(deftest sync-configured-repos-no-repos-test
  (testing "No configured repos returns failure"
    (let [state (atom {:pr-train-manager :mgr})]
      (with-redefs-fn {#'sut/get-configured-repos (fn [_] [])}
        (fn []
          (let [result (sut/sync-configured-repos! state)]
            (is (false? (:success? result)))
            (is (= 0 (:synced result)))
            (is (some? (:fleet/last-sync @state)))))))))

(deftest sync-configured-repos-no-manager-test
  (testing "No pr-train-manager returns failure"
    (let [state (atom {})]
      (with-redefs-fn {#'sut/get-configured-repos (fn [_] ["acme/repo"])}
        (fn []
          (let [result (sut/sync-configured-repos! state)]
            (is (false? (:success? result)))
            (is (= 1 (:failed result)))))))))

(deftest sync-configured-repos-aggregates-results-and-failures-test
  (let [state (atom {:pr-train-manager :manager})
        sync-results {"acme/service"
                      {:success? true
                       :success true
                       :repo "acme/service"
                       :added 2
                       :removed 1
                       :tracked-prs 3}
                      "acme/web"
                      {:success? false
                       :success false
                       :repo "acme/web"
                       :error "Provider unavailable"
                       :anomaly (response/make-anomaly :anomalies/unavailable "Provider unavailable")}}]
    (with-redefs-fn {#'sut/get-configured-repos (fn [_] ["acme/service" "acme/web"])
                     #'sut/sync-repo-prs-into-train! (fn [_ repo] (get sync-results repo))}
      (fn []
        (let [result (sut/sync-configured-repos! state)]
          (is (false? (:success? result)))
          (is (= 1 (:synced result)))
          (is (= 1 (:failed result)))
          (is (= 2 (count (:results result))))
          (is (= {:added-prs 2
                  :removed-prs 1
                  :tracked-prs 3}
                 (:summary result)))
          (is (= :anomalies/fault (get-in result [:anomaly :anomaly/category]))))))))

(deftest sync-configured-repos-all-success-test
  (testing "All repos succeed produces success result"
    (let [state (atom {:pr-train-manager :manager})]
      (with-redefs-fn {#'sut/get-configured-repos (fn [_] ["acme/a"])
                       #'sut/sync-repo-prs-into-train!
                       (fn [_ _]
                         {:success? true :success true :repo "acme/a"
                          :added 1 :removed 0 :tracked-prs 1})}
        (fn []
          (let [result (sut/sync-configured-repos! state)]
            (is (true? (:success? result)))
            (is (= 1 (:synced result)))
            (is (= 0 (:failed result)))))))))

;; ============================================================================
;; Integration: flaky provider response simulation
;; ============================================================================

(deftest flaky-provider-intermittent-failure-test
  (testing "When provider fails intermittently, partial sync is recorded"
    (let [call-count (atom 0)
          state (atom {:pr-train-manager :manager})]
      (with-redefs-fn {#'sut/get-configured-repos
                       (fn [_] ["acme/service" "acme/web" "acme/api"])
                       #'sut/sync-repo-prs-into-train!
                       (fn [_ repo]
                         (swap! call-count inc)
                         (case repo
                           "acme/service"
                           {:success? true :success true :repo repo
                            :added 2 :removed 0 :tracked-prs 2}
                           "acme/web"
                           {:success? false :success false :repo repo
                            :error "connection timed out"}
                           "acme/api"
                           {:success? true :success true :repo repo
                            :added 1 :removed 0 :tracked-prs 1}))}
        (fn []
          (let [result (sut/sync-configured-repos! state)]
            (is (= 3 @call-count))
            (is (= 2 (:synced result)))
            (is (= 1 (:failed result)))
            (let [ls (:fleet/last-sync @state)]
              (is (= :partial (:status ls)))
              (is (= 1 (count (:failures ls))))
              (is (= "acme/web" (:repo (first (:failures ls)))))
              (is (= :network (:error-category (first (:failures ls))))))))))))

(deftest flaky-provider-all-repos-fail-test
  (testing "When all repos fail, status is :failed"
    (let [state (atom {:pr-train-manager :manager})]
      (with-redefs-fn {#'sut/get-configured-repos
                       (fn [_] ["acme/a" "acme/b"])
                       #'sut/sync-repo-prs-into-train!
                       (fn [_ repo]
                         {:success? false :success false :repo repo
                          :error "403 Forbidden"})}
        (fn []
          (let [result (sut/sync-configured-repos! state)]
            (is (= 0 (:synced result)))
            (is (= 2 (:failed result)))
            (let [ls (:fleet/last-sync @state)]
              (is (= :failed (:status ls)))
              (is (= 2 (count (:failures ls))))
              (is (every? #(= :access (:error-category %)) (:failures ls))))))))))

(deftest sync-exception-in-repo-does-not-crash-fleet-test
  (testing "Exception in one repo sync is caught and does not crash fleet sync"
    (let [state (atom {:pr-train-manager :manager})]
      (with-redefs-fn {#'sut/get-configured-repos
                       (fn [_] ["acme/ok" "acme/boom"])
                       #'sut/fetch-open-prs
                       (fn [repo]
                         (if (= repo "acme/boom")
                           (throw (ex-info "kaboom" {:repo repo}))
                           {:success? true :success true
                            :prs [{:pr/number 1 :pr/title "PR" :pr/url "u" :pr/branch "b"
                                   :pr/status :open :pr/ci-status :pending}]
                            :repo repo}))
                       #'sut/ensure-repo-train!
                       (fn [_ _] (random-uuid))
                       #'sut/ensure-default-dag-id!
                       (fn [_] (random-uuid))
                       #'sut/ensure-repo-in-dag!
                       (fn [_ _ _] nil)
                       #'sut/apply-sync-plan!
                       (fn [_ _ _ _] nil)
                       #'core/safe-call
                       (fn [_ fn-sym & _]
                         (case fn-sym
                           get-train {:train/prs []}
                           nil))}
        (fn []
          (let [result (sut/sync-configured-repos! state)]
            (is (= 1 (:synced result)))
            (is (= 1 (:failed result)))
            (is (some? (:fleet/last-sync @state)))))))))

;; ============================================================================
;; Integration: multi-repo train with blocked dependency
;; ============================================================================

(deftest multi-repo-train-blocked-dependency-readiness-test
  (testing "Multi-repo train: one dep blocked, deterministic readiness rendering"
    (let [prs [{:pr/number 1 :pr/repo "acme/repo-a" :pr/title "Infra change"
                :pr/status :open :pr/ci-status :failed
                :pr/merge-order 1 :pr/depends-on [] :pr/behind-main? false}
               {:pr/number 2 :pr/repo "acme/repo-b" :pr/title "App change"
                :pr/status :approved :pr/ci-status :passed
                :pr/merge-order 2 :pr/depends-on [1] :pr/behind-main? false}
               {:pr/number 3 :pr/repo "acme/repo-b" :pr/title "Docs update"
                :pr/status :approved :pr/ci-status :passed
                :pr/merge-order 3 :pr/depends-on [] :pr/behind-main? false}]
          pr-map (into {} (map (juxt :pr/number identity) prs))]

      (testing "PR#1 (upstream, CI failing) has :ci-failing readiness state"
        (let [r (sut/readiness pr-map (get pr-map 1))]
          (is (= :ci-failing (:readiness/state r)))
          (is (false? (:readiness/ready? r)))
          (is (some #(= :ci (:blocker/type %)) (:readiness/blockers r)))))

      (testing "PR#2 (downstream, dep blocked) has :dep-blocked readiness state"
        (let [r (sut/readiness pr-map (get pr-map 2))]
          (is (= :dep-blocked (:readiness/state r)))
          (is (false? (:readiness/ready? r)))
          (is (some #(= :dependency (:blocker/type %)) (:readiness/blockers r)))
          (is (some #(re-find #"#1" (:blocker/message %))
                    (:readiness/blockers r)))))

      (testing "PR#3 (independent, all green) has :merge-ready readiness state"
        (let [r (sut/readiness pr-map (get pr-map 3))]
          (is (= :merge-ready (:readiness/state r)))
          (is (true? (:readiness/ready? r)))
          (is (empty? (:readiness/blockers r)))))

      (testing "Readiness scores are monotonically: PR#3 > PR#2, PR#3 > PR#1"
        (let [s1 (:readiness/score (sut/readiness pr-map (get pr-map 1)))
              s2 (:readiness/score (sut/readiness pr-map (get pr-map 2)))
              s3 (:readiness/score (sut/readiness pr-map (get pr-map 3)))]
          (is (> s3 s1) "Ready PR scores higher than CI-failing")
          (is (> s3 s2) "Ready PR scores higher than dep-blocked"))))))

(deftest multi-repo-train-one-dep-resolves-test
  (testing "When upstream dep merges, downstream becomes merge-ready"
    (let [prs-before [{:pr/number 1 :pr/repo "acme/repo-a" :pr/title "Infra"
                       :pr/status :open :pr/ci-status :failed
                       :pr/merge-order 1 :pr/depends-on [] :pr/behind-main? false}
                      {:pr/number 2 :pr/repo "acme/repo-b" :pr/title "App"
                       :pr/status :approved :pr/ci-status :passed
                       :pr/merge-order 2 :pr/depends-on [1] :pr/behind-main? false}]
          pm-before (into {} (map (juxt :pr/number identity) prs-before))
          prs-after [{:pr/number 1 :pr/repo "acme/repo-a" :pr/title "Infra"
                      :pr/status :merged :pr/ci-status :passed
                      :pr/merge-order 1 :pr/depends-on [] :pr/behind-main? false}
                     {:pr/number 2 :pr/repo "acme/repo-b" :pr/title "App"
                      :pr/status :approved :pr/ci-status :passed
                      :pr/merge-order 2 :pr/depends-on [1] :pr/behind-main? false}]
          pm-after (into {} (map (juxt :pr/number identity) prs-after))]
      (is (= :dep-blocked (:readiness/state (sut/readiness pm-before (get pm-before 2)))))
      (is (= :merge-ready (:readiness/state (sut/readiness pm-after (get pm-after 2))))))))

;; ============================================================================
;; Layer 4: DAG composite state
;; ============================================================================

(deftest get-dag-state-empty-test
  (testing "Returns empty structure when no dags/trains"
    (let [state (atom {})]
      (with-redefs-fn {#'sut/get-dags (fn [_] [])
                       #'sut/get-trains (fn [_] [])}
        (fn []
          (let [result (sut/get-dag-state state)]
            (is (= [] (:dags result)))
            (is (= [] (:trains result)))
            (is (empty? (:repos result)))
            (is (empty? (:tasks result)))))))))

(deftest get-dag-state-maps-pr-statuses-test
  (testing "PR statuses map to task statuses correctly"
    (let [state (atom {})
          trains [{:train/id (random-uuid)
                   :train/prs [{:pr/number 1 :pr/status :draft :pr/repo "a/b"
                                :pr/title "Draft PR" :pr/depends-on []}
                               {:pr/number 2 :pr/status :reviewing :pr/repo "a/b"
                                :pr/title "In Review" :pr/depends-on []}
                               {:pr/number 3 :pr/status :merged :pr/repo "a/b"
                                :pr/title "Merged PR" :pr/depends-on []}
                               {:pr/number 4 :pr/status :failed :pr/repo "a/b"
                                :pr/title "Failed PR" :pr/depends-on []}
                               {:pr/number 5 :pr/status :approved :pr/repo "a/b"
                                :pr/title "Approved PR" :pr/depends-on []}]}]]
      (with-redefs-fn {#'sut/get-dags (fn [_] [])
                       #'sut/get-trains (fn [_] trains)}
        (fn []
          (let [result (sut/get-dag-state state)
                tasks-by-id (into {} (map (juxt :id identity) (:tasks result)))]
            (is (= 5 (count (:tasks result))))
            (is (= :ready (:status (get tasks-by-id 1))))
            (is (= :running (:status (get tasks-by-id 2))))
            (is (= :done (:status (get tasks-by-id 3))))
            (is (= :blocked (:status (get tasks-by-id 4))))
            (is (= :blocked (:status (get tasks-by-id 5))))))))))

;; ============================================================================
;; Discover configured repos tests
;; ============================================================================

(deftest discover-configured-repos-success-test
  (testing "Discovers and adds repos from provider"
    (let [config-path (temp-config-path)
          state (atom {})]
      (with-redefs-fn {#'sut/default-fleet-config-path config-path
                       #'sut/run-gh
                       (fn [& _args]
                         {:success? true
                          :out (json/generate-string
                                [{:full_name "Acme/Repo1"}
                                 {:full_name "Acme/Repo2"}])
                          :err ""})}
        (fn []
          (let [result (sut/discover-configured-repos! state {:owner "acme" :limit 10})]
            (is (true? (:success? result)))
            (is (= 2 (:discovered result)))
            (is (= 2 (:added result)))
            (is (= ["acme/repo1" "acme/repo2"] (:repos result)))))))))

(deftest discover-configured-repos-deduplicates-test
  (testing "Does not re-add existing repos"
    (let [config-path (temp-config-path)
          state (atom {})]
      (spit config-path (pr-str {:fleet {:repos ["acme/repo1"]}}))
      (with-redefs-fn {#'sut/default-fleet-config-path config-path
                       #'sut/run-gh
                       (fn [& _args]
                         {:success? true
                          :out (json/generate-string
                                [{:full_name "Acme/Repo1"}
                                 {:full_name "Acme/Repo2"}])
                          :err ""})}
        (fn []
          (let [result (sut/discover-configured-repos! state {:owner "acme"})]
            (is (true? (:success? result)))
            (is (= 1 (:added result)))
            (is (= 2 (count (:repos result))))))))))

(deftest discover-configured-repos-provider-failure-test
  (testing "Provider failure is propagated"
    (let [config-path (temp-config-path)
          state (atom {})]
      (with-redefs-fn {#'sut/default-fleet-config-path config-path
                       #'sut/run-gh
                       (fn [& _args]
                         {:success? false :out "" :err "not logged in"})}
        (fn []
          (let [result (sut/discover-configured-repos! state {:owner "acme"})]
            (is (false? (:success? result)))))))))

(deftest discover-configured-repos-limit-test
  (testing "Respects limit parameter"
    (let [config-path (temp-config-path)
          state (atom {})
          many-repos (mapv (fn [i] {:full_name (str "acme/repo" i)}) (range 100))]
      (with-redefs-fn {#'sut/default-fleet-config-path config-path
                       #'sut/run-gh
                       (fn [& _args]
                         {:success? true
                          :out (json/generate-string many-repos)
                          :err ""})}
        (fn []
          (let [result (sut/discover-configured-repos! state {:limit 5})]
            (is (true? (:success? result)))
            (is (= 5 (:discovered result)))))))))
