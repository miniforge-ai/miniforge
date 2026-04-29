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

(ns ai.miniforge.web-dashboard.views.fleet-view-gaps-test
  "Additional tests for fleet view functions not covered in fleet-view-test:
   - format-sync-time
   - fleet-action-onclick for all actions
   - error-category-variant for all categories
   - sync-failure-entry rendering
   - train-detail-view error case
   - train-list-fragment empty state
   - sync-status-fragment nil (no sync)
   - readiness-state-label/variant edge cases (unknown state)
   - error-category-label edge cases (unknown category)"
  (:require
   [clojure.test :refer [deftest is testing are]]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.views.fleet :as sut]))

(defn mock-layout [title body]
  [:html [:head [:title title]] [:body body]])

(defn flat-strings
  [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (filter string?)))

(defn contains-string?
  [hiccup substr]
  (some #(str/includes? % substr) (flat-strings hiccup)))

;; ============================================================================
;; format-sync-time
;; ============================================================================

(deftest format-sync-time-valid-date-test
  (testing "Formats a java.util.Date into expected pattern"
    (let [ts (java.util.Date. 1700000000000) ;; 2023-11-14
          result (sut/format-sync-time ts)]
      (is (string? result))
      (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}" result)))))

(deftest format-sync-time-nil-test
  (testing "Returns nil for nil timestamp"
    (is (nil? (sut/format-sync-time nil)))))

(deftest format-sync-time-invalid-input-test
  (testing "Returns nil for invalid input"
    (is (nil? (sut/format-sync-time "not-a-date")))))

;; ============================================================================
;; fleet-action-onclick
;; ============================================================================

(deftest fleet-action-onclick-all-actions-test
  (testing "All known actions produce non-empty onclick strings"
    (are [action expected-substr]
      (str/includes? (sut/fleet-action-onclick action) expected-substr)
      :add-repo "addRepo"
      :discover-repos "discoverRepos"
      :sync-prs "syncPrs"
      :discover-sync "discoverAndSync")))

(deftest fleet-action-onclick-unknown-action-test
  (testing "Unknown action returns empty string"
    (is (= "" (sut/fleet-action-onclick :unknown-action)))
    (is (= "" (sut/fleet-action-onclick nil)))))

;; ============================================================================
;; error-category-variant
;; ============================================================================

(deftest error-category-variant-all-categories-test
  (testing "All error categories map to a badge variant keyword"
    (are [cat expected]
      (= expected (sut/error-category-variant cat))
      :auth :error
      :auth-failure :error
      :access :error
      :not-found :error
      :rate-limit :warning
      :rate-limited :warning
      :parse :warning
      :parse-error :warning
      :network :warning
      :network-error :warning)))

(deftest error-category-variant-unknown-test
  (testing "Unknown category defaults to :error"
    (is (= :error (sut/error-category-variant :some-weird-thing)))
    (is (= :error (sut/error-category-variant nil)))))

;; ============================================================================
;; readiness-state-label edge cases
;; ============================================================================

(deftest readiness-state-label-unknown-keyword-test
  (testing "Unknown keyword returns its name"
    (is (= "some-state" (sut/readiness-state-label :some-state)))))

(deftest readiness-state-label-nil-test
  (testing "Nil state returns unknown label"
    (is (contains? #{"Unknown" "unknown"} (sut/readiness-state-label nil)))))

;; ============================================================================
;; readiness-state-variant edge cases
;; ============================================================================

(deftest readiness-state-variant-unknown-keyword-test
  (testing "Unknown keyword returns :neutral"
    (is (= :neutral (sut/readiness-state-variant :some-unknown-state)))))

;; ============================================================================
;; error-category-label edge cases
;; ============================================================================

(deftest error-category-label-unknown-keyword-test
  (testing "Unknown keyword returns its name"
    (is (= "weird-cat" (sut/error-category-label :weird-cat)))))

(deftest error-category-label-nil-test
  (testing "Nil category returns 'Error'"
    (is (= "Error" (sut/error-category-label nil)))))

;; ============================================================================
;; sync-failure-entry
;; ============================================================================

(deftest sync-failure-entry-full-test
  (testing "Renders repo, error, action, and error-category badge"
    (let [entry {:repo "acme/broken"
                 :error "401 Unauthorized"
                 :action "Run gh auth login"
                 :error-category :auth}
          result (sut/sync-failure-entry entry)]
      (is (vector? result))
      (is (contains-string? result "acme/broken"))
      (is (contains-string? result "401 Unauthorized"))
      (is (contains-string? result "Run gh auth login"))
      (is (or (contains-string? result "Auth")
              (contains-string? result "auth"))))))

(deftest sync-failure-entry-no-category-test
  (testing "Entry without error-category omits badge"
    (let [entry {:repo "acme/other" :error "something" :action nil :error-category nil}
          result (sut/sync-failure-entry entry)]
      (is (contains-string? result "acme/other"))
      (is (contains-string? result "something")))))

(deftest sync-failure-entry-blank-action-test
  (testing "Entry with blank action omits action span"
    (let [entry {:repo "a/b" :error "err" :action "   " :error-category nil}
          result (sut/sync-failure-entry entry)]
      (is (contains-string? result "err"))
      ;; No sync-action span for blank action
      (is (not (some #(and (vector? %) (= :span.sync-action (first %)))
                     (tree-seq coll? seq result)))))))

;; ============================================================================
;; sync-status-fragment nil (no sync run)
;; ============================================================================

(deftest sync-status-fragment-nil-test
  (testing "Nil last-sync shows 'No sync run yet' message"
    (let [result (sut/sync-status-fragment nil)]
      (is (vector? result))
      (is (contains-string? result "No sync run yet")))))

(deftest sync-status-fragment-failed-status-test
  (testing "Failed status renders with error variant"
    (let [result (sut/sync-status-fragment
                  {:status :failed
                   :timestamp (java.util.Date.)
                   :synced 0 :failed 3
                   :failures []})]
      (is (contains-string? result "failed"))
      (is (contains-string? result "synced 0")))))

;; ============================================================================
;; train-list-fragment empty state
;; ============================================================================

(deftest train-list-fragment-empty-test
  (testing "Empty train list shows empty state with CTA buttons"
    (let [html-str (str (sut/train-list-fragment []))]
      (is (str/includes? html-str "No PR Trains Yet"))
      (is (str/includes? html-str "Run Workflow"))
      (is (str/includes? html-str "Discover + Sync")))))

;; ============================================================================
;; train-detail-view error case
;; ============================================================================

(deftest train-detail-view-error-test
  (testing "Train with :error key renders error message"
    (let [result (sut/train-detail-view mock-layout {:error "Train not found"})]
      (is (contains-string? result "Train not found"))
      ;; Title should be "Error"
      (is (contains-string? result "Error")))))

(deftest train-detail-view-no-prs-test
  (testing "Train with empty PR list renders without errors"
    (let [train {:train/id (random-uuid)
                 :train/name "Empty Train"
                 :train/description "No PRs"
                 :train/prs []}
          result (sut/train-detail-view mock-layout train)]
      (is (contains-string? result "Empty Train")))))

;; ============================================================================
;; blocking-reason-line all blocker types
;; ============================================================================

(deftest blocking-reason-line-all-types-test
  (testing "All blocker types render with appropriate variant"
    (doseq [type [:dependency :ci :review :policy :conflict]]
      (let [result (sut/blocking-reason-line {:blocker/type type
                                              :blocker/message (str type " issue")})]
        (is (vector? result) (str "Result for " type " should be a vector"))
        (is (contains-string? result (name type)))))))

(deftest blocking-reason-line-no-message-test
  (testing "Missing message defaults to 'Blocked'"
    (let [result (sut/blocking-reason-line {:blocker/type :ci :blocker/message nil})]
      (is (contains-string? result "Blocked")))))

;; ============================================================================
;; pr-readiness-fragment score formatting
;; ============================================================================

(deftest pr-readiness-fragment-no-score-test
  (testing "Readiness without score omits score span"
    (let [result (sut/pr-readiness-fragment {:readiness/state :needs-review
                                             :readiness/score nil})]
      (is (contains-string? result "Needs Review"))
      ;; No readiness-score span
      (is (not (some #(and (vector? %) (= :span.readiness-score (first %)))
                     (tree-seq coll? seq result)))))))

(deftest pr-readiness-fragment-zero-score-test
  (testing "Zero score renders as 0.00"
    (let [result (sut/pr-readiness-fragment {:readiness/state :ci-failing
                                             :readiness/score 0.0})]
      (is (contains-string? result "0.00")))))

(deftest pr-readiness-fragment-perfect-score-test
  (testing "Perfect score renders as 1.00"
    (let [result (sut/pr-readiness-fragment {:readiness/state :merge-ready
                                             :readiness/score 1.0})]
      (is (contains-string? result "1.00")))))