;; Copyright 2025 miniforge.ai
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.web-dashboard.state.trains-classify-test
  "Unit tests for classify-error-category, gh-error-message, train naming,
   readiness-factors structure, pr-ready?, readiness-summary, blocking-details,
   prs->status-map, and other Layer 1-3 functions with coverage gaps."
  (:require
   [clojure.test :refer [deftest is testing are]]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.state.trains :as sut]))

;; ============================================================================
;; classify-error-category (Layer 3)
;; ============================================================================

(deftest classify-error-category-auth-test
  (testing "Auth-related messages classified as :auth"
    (are [msg] (= :auth (sut/classify-error-category msg))
      "authentication failed"
      "HTTP 401 Bad credentials: auth error"
      "You are not logged in to any GitHub hosts"
      "AUTHENTICATION REQUIRED")))

(deftest classify-error-category-access-test
  (testing "Access-related messages classified as :access"
    (are [msg] (= :access (sut/classify-error-category msg))
      "repository not found"
      "403 Forbidden"
      "permission denied for this resource"
      "access denied for org/repo")))

(deftest classify-error-category-rate-limit-test
  (testing "Rate limit messages classified as :rate-limit"
    (are [msg] (= :rate-limit (sut/classify-error-category msg))
      "API rate limit exceeded"
      "secondary rate limit triggered"
      "Rate Limit hit")))

(deftest classify-error-category-parse-test
  (testing "Parse/malformed messages classified as :parse"
    (are [msg] (= :parse (sut/classify-error-category msg))
      "failed to parse response"
      "malformed JSON body"
      "invalid json token at position 0")))

(deftest classify-error-category-network-test
  (testing "Network/timeout messages classified as :network"
    (are [msg] (= :network (sut/classify-error-category msg))
      "connection refused"
      "request timeout after 30s"
      "timed out waiting for response"
      "network unreachable"
      "Connection reset by peer")))

(deftest classify-error-category-unknown-test
  (testing "Unrecognized messages classified as :unknown"
    (are [msg] (= :unknown (sut/classify-error-category msg))
      "something went wrong"
      "unexpected error code 500"
      ""
      nil)))

(deftest classify-error-category-case-insensitive-test
  (testing "Classification is case-insensitive"
    (is (= :auth (sut/classify-error-category "AUTHENTICATION REQUIRED")))
    (is (= :network (sut/classify-error-category "CONNECTION TIMED OUT")))
    (is (= :access (sut/classify-error-category "NOT FOUND")))))

;; ============================================================================
;; gh-error-message
;; ============================================================================

(deftest gh-error-message-prefers-err-test
  (testing "Returns err when present"
    (is (= "bad auth" (sut/gh-error-message "" "bad auth"))))
  (testing "Falls back to out when err is blank"
    (is (= "some output" (sut/gh-error-message "some output" ""))))
  (testing "Returns fallback when both are blank"
    (let [msg (sut/gh-error-message "" "")]
      (is (string? msg))
      (is (pos? (count msg))))))

(deftest gh-error-message-nil-handling-test
  (testing "Handles nil err gracefully"
    (is (string? (sut/gh-error-message "output" nil)))))

;; ============================================================================
;; train-name-for-repo
;; ============================================================================

(deftest train-name-for-repo-test
  (testing "Prefixes repo slug with external train prefix"
    (is (= "External PRs: acme/service" (sut/train-name-for-repo "acme/service"))))
  (testing "Prefix matches external-train-prefix constant"
    (is (str/starts-with? (sut/train-name-for-repo "a/b") sut/external-train-prefix))))

;; ============================================================================
;; prs->status-map
;; ============================================================================

(deftest prs->status-map-test
  (testing "Maps PR number to status/ci-status pair"
    (let [prs [{:pr/number 1 :pr/status :open :pr/ci-status :pending}
               {:pr/number 2 :pr/status :approved :pr/ci-status :passed}]
          result (sut/prs->status-map prs)]
      (is (= {1 {:pr/status :open :pr/ci-status :pending}
              2 {:pr/status :approved :pr/ci-status :passed}}
             result))))
  (testing "Empty list returns empty map"
    (is (= {} (sut/prs->status-map [])))))

;; ============================================================================
;; pr-ready? (Layer 1)
;; ============================================================================

(deftest pr-ready?-fully-ready-test
  (testing "Approved, CI passed, deps clear, not behind, gates passed => ready"
    (let [pm {1 {:pr/number 1 :pr/status :merged}
              2 {:pr/number 2 :pr/status :approved :pr/ci-status :passed
                 :pr/depends-on [1] :pr/behind-main? false}}]
      (is (true? (sut/pr-ready? pm (get pm 2)))))))

(deftest pr-ready?-no-deps-test
  (testing "Approved PR with no deps and all green is ready"
    (let [pm {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed
                 :pr/behind-main? false}}]
      (is (true? (sut/pr-ready? pm (get pm 1)))))))

(deftest pr-ready?-ci-failed-test
  (testing "PR with failed CI is not ready"
    (let [pm {1 {:pr/number 1 :pr/status :approved :pr/ci-status :failed
                 :pr/behind-main? false}}]
      (is (false? (sut/pr-ready? pm (get pm 1)))))))

(deftest pr-ready?-behind-main-test
  (testing "PR behind main is not ready"
    (let [pm {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed
                 :pr/behind-main? true}}]
      (is (false? (sut/pr-ready? pm (get pm 1)))))))

(deftest pr-ready?-unresolved-deps-test
  (testing "PR with unresolved deps is not ready"
    (let [pm {1 {:pr/number 1 :pr/status :open}
              2 {:pr/number 2 :pr/status :approved :pr/ci-status :passed
                 :pr/depends-on [1] :pr/behind-main? false}}]
      (is (false? (sut/pr-ready? pm (get pm 2)))))))

(deftest pr-ready?-open-status-test
  (testing "Open (not approved) PR is not ready even with CI passed"
    (let [pm {1 {:pr/number 1 :pr/status :open :pr/ci-status :passed
                 :pr/behind-main? false}}]
      (is (not (sut/pr-ready? pm (get pm 1)))))))

(deftest pr-ready?-failed-gates-test
  (testing "PR with failed gates is not ready"
    (let [pm {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed
                 :pr/behind-main? false
                 :pr/gate-results [{:gate/passed? false}]}}]
      (is (false? (sut/pr-ready? pm (get pm 1)))))))

(deftest pr-ready?-merging-status-test
  (testing "PR in :merging status with all green is ready"
    (let [pm {1 {:pr/number 1 :pr/status :merging :pr/ci-status :passed
                 :pr/behind-main? false}}]
      (is (true? (sut/pr-ready? pm (get pm 1)))))))

(deftest pr-ready?-merged-status-test
  (testing "Merged PR is ready"
    (let [pm {1 {:pr/number 1 :pr/status :merged :pr/ci-status :passed
                 :pr/behind-main? false}}]
      (is (true? (sut/pr-ready? pm (get pm 1)))))))

;; ============================================================================
;; readiness-factors structure
;; ============================================================================

(deftest readiness-factors-structure-test
  (testing "Returns exactly 5 factors in canonical order with expected keys"
    (let [pm {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed
                 :pr/behind-main? false}}
          factors (sut/readiness-factors pm (get pm 1))]
      (is (= 5 (count factors)))
      (is (= sut/readiness-factor-order (mapv :factor factors)))
      (doseq [f factors]
        (is (contains? f :factor))
        (is (contains? f :weight))
        (is (contains? f :score))
        (is (contains? f :contribution))
        (is (double? (:score f)))
        (is (double? (:contribution f)))
        (is (<= 0.0 (:score f) 1.0))))))

(deftest readiness-factors-all-perfect-test
  (testing "Perfect PR has all scores at 1.0"
    (let [pm {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed
                 :pr/behind-main? false}}
          factors (sut/readiness-factors pm (get pm 1))
          scores (mapv :score factors)]
      (is (every? #(= 1.0 %) scores)))))

(deftest readiness-factors-all-bad-test
  (testing "PR with all bad signals has all scores at 0.0 or low"
    (let [pm {1 {:pr/number 1 :pr/status :draft :pr/ci-status :failed
                 :pr/behind-main? true
                 :pr/gate-results [{:gate/passed? false}]
                 :pr/depends-on [99]}}
          factors (sut/readiness-factors pm (get pm 1))
          total (reduce + 0.0 (map :contribution factors))]
      (is (< total 0.2) "Total score should be very low for worst-case PR"))))

;; ============================================================================
;; readiness-summary
;; ============================================================================

(deftest readiness-summary-test
  (testing "Counts readiness states across PRs"
    (let [prs [{:pr/readiness {:readiness/state :merge-ready}}
               {:pr/readiness {:readiness/state :merge-ready}}
               {:pr/readiness {:readiness/state :ci-failing}}
               {:pr/readiness {:readiness/state :needs-review}}]
          summary (sut/readiness-summary prs)]
      (is (= 2 (get summary :merge-ready)))
      (is (= 1 (get summary :ci-failing)))
      (is (= 1 (get summary :needs-review))))))

(deftest readiness-summary-empty-test
  (testing "Empty PR list returns empty map"
    (is (= {} (sut/readiness-summary [])))))

(deftest readiness-summary-missing-readiness-test
  (testing "PRs without readiness default to :unknown"
    (let [summary (sut/readiness-summary [{} {:pr/readiness nil}])]
      (is (= 2 (get summary :unknown))))))

;; ============================================================================
;; blocking-details
;; ============================================================================

(deftest blocking-details-test
  (testing "Extracts blocking details for blocking PR numbers"
    (let [prs [{:pr/number 1 :pr/repo "a/b" :pr/title "PR 1"
                :pr/blocking-reasons ["CI failed" "Behind main"]}
               {:pr/number 2 :pr/repo "a/b" :pr/title "PR 2"
                :pr/blocking-reasons ["Dep blocked"]}]
          result (sut/blocking-details prs [1 2])]
      (is (= 2 (count result)))
      (is (= 1 (:pr/number (first result))))
      (is (= ["CI failed" "Behind main"] (:blocking/reasons (first result)))))))

(deftest blocking-details-missing-pr-test
  (testing "Missing PR numbers are silently skipped"
    (let [prs [{:pr/number 1 :pr/repo "a/b" :pr/title "PR 1"
                :pr/blocking-reasons []}]
          result (sut/blocking-details prs [1 99])]
      (is (= 1 (count result))))))

(deftest blocking-details-empty-test
  (testing "No blocking PRs returns empty vector"
    (is (= [] (sut/blocking-details [{:pr/number 1}] [])))))

;; ============================================================================
;; enrich-pr composite
;; ============================================================================

(deftest enrich-pr-adds-all-keys-test
  (testing "enrich-pr adds :pr/readiness and :pr/blocking-reasons"
    (let [pm {1 {:pr/number 1 :pr/status :open :pr/ci-status :pending :pr/behind-main? false}}
          enriched (sut/enrich-pr pm (get pm 1))]
      (is (contains? enriched :pr/readiness))
      (is (contains? enriched :pr/blocking-reasons))
      (is (map? (:pr/readiness enriched)))
      (is (vector? (:pr/blocking-reasons enriched))))))

(deftest enrich-pr-blocking-reasons-match-blockers-test
  (testing "blocking-reasons are the messages from readiness blockers"
    (let [pm {1 {:pr/number 1 :pr/status :open :pr/ci-status :failed :pr/behind-main? true}}
          enriched (sut/enrich-pr pm (get pm 1))
          blockers (get-in enriched [:pr/readiness :readiness/blockers])
          reasons (:pr/blocking-reasons enriched)]
      (is (= (mapv :blocker/message blockers) reasons)))))

;; ============================================================================
;; readiness-state priority order verification
;; ============================================================================

(deftest readiness-state-priority-ci-over-dep-blocked-test
  (testing "CI failing takes precedence over dep-blocked"
    (let [pm {1 {:pr/number 1 :pr/status :open}
              2 {:pr/number 2 :pr/status :approved :pr/ci-status :failed
                 :pr/depends-on [1] :pr/behind-main? false}}]
      (is (= :ci-failing (sut/readiness-state pm (get pm 2)))))))

(deftest readiness-state-priority-changes-requested-over-dep-blocked-test
  (testing "Changes-requested takes precedence over dep-blocked"
    (let [pm {1 {:pr/number 1 :pr/status :open}
              2 {:pr/number 2 :pr/status :changes-requested :pr/ci-status :passed
                 :pr/depends-on [1] :pr/behind-main? false}}]
      (is (= :changes-requested (sut/readiness-state pm (get pm 2)))))))

(deftest readiness-state-priority-dep-blocked-over-merge-conflicts-test
  (testing "Dep-blocked takes precedence over merge-conflicts"
    (let [pm {1 {:pr/number 1 :pr/status :open}
              2 {:pr/number 2 :pr/status :approved :pr/ci-status :passed
                 :pr/depends-on [1] :pr/behind-main? true}}]
      (is (= :dep-blocked (sut/readiness-state pm (get pm 2)))))))

(deftest readiness-state-priority-merge-conflicts-over-policy-test
  (testing "Merge-conflicts takes precedence over policy-failing"
    (let [pm {1 {:pr/number 1 :pr/status :approved :pr/ci-status :passed
                 :pr/behind-main? true
                 :pr/gate-results [{:gate/passed? false}]}}]
      (is (= :merge-conflicts (sut/readiness-state pm (get pm 1)))))))

;; ============================================================================
;; pr-map helper
;; ============================================================================

(deftest pr-map-test
  (testing "Creates a map keyed by PR number"
    (let [prs [{:pr/number 1 :pr/title "A"}
               {:pr/number 2 :pr/title "B"}]
          result (sut/pr-map prs)]
      (is (= 2 (count result)))
      (is (= "A" (:pr/title (get result 1))))
      (is (= "B" (:pr/title (get result 2)))))))

(deftest pr-map-empty-test
  (testing "Empty list returns empty map"
    (is (= {} (sut/pr-map [])))))

;; ============================================================================
;; gate-results helper
;; ============================================================================

(deftest gate-results-nil-test
  (testing "Missing/nil gate-results returns empty vector"
    (is (= [] (sut/gate-results {})))
    (is (= [] (sut/gate-results {:pr/gate-results nil})))))

(deftest gate-results-non-sequential-test
  (testing "Non-sequential gate-results returns empty vector"
    (is (= [] (sut/gate-results {:pr/gate-results :not-a-list})))))

;; ============================================================================
;; Readiness threshold constant sanity
;; ============================================================================

(deftest readiness-threshold-sanity-test
  (testing "Threshold is between 0 and 1"
    (is (<= 0.0 sut/readiness-threshold 1.0))))

(deftest readiness-weights-sum-to-one-test
  (testing "All readiness weights sum to 1.0"
    (let [total (reduce + 0.0 (vals sut/readiness-weights))]
      (is (< (Math/abs (- total 1.0)) 0.001)))))