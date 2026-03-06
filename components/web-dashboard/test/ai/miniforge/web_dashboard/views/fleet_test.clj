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

(ns ai.miniforge.web-dashboard.views.fleet-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.views.fleet :as sut]))

;; ============================================================================
;; sync-status-fragment tests
;; ============================================================================

(deftest sync-status-fragment-nil-test
  (testing "Nil last-sync renders 'no sync' message"
    (let [result (#'sut/sync-status-fragment nil)]
      (is (vector? result))
      (is (= :div.fleet-sync-status (first result)))
      ;; Should contain 'No sync run yet' somewhere in the structure
      (is (some #(and (string? %) (str/includes? % "No sync"))
               (flatten (tree-seq coll? seq result)))))))

(deftest sync-status-fragment-success-test
  (testing "Success sync renders success badge and counts"
    (let [result (#'sut/sync-status-fragment {:status :success
                                            :timestamp (java.util.Date.)
                                            :synced 3
                                            :failed 0
                                            :failures []})]
      (is (vector? result))
      ;; Should contain 'success' somewhere
      (is (some #(and (string? %) (str/includes? % "success"))
               (flatten (tree-seq coll? seq result)))))))

(deftest sync-status-fragment-with-failures-test
  (testing "Failures render with error details and action hints"
    (let [result (#'sut/sync-status-fragment {:status :partial
                                            :timestamp (java.util.Date.)
                                            :synced 2
                                            :failed 1
                                            :message "Sync partially failed"
                                            :failures [{:repo "acme/web"
                                                        :error "auth required"
                                                        :error-category :auth
                                                        :action "Run gh auth login"}]})]
      (is (vector? result))
      ;; Should contain repo name
      (let [flat (flatten (tree-seq coll? seq result))]
        (is (some #(and (string? %) (str/includes? % "acme/web")) flat))
        ;; Should contain action hint
        (is (some #(and (string? %) (str/includes? % "gh auth login")) flat))))))

(deftest sync-status-fragment-failed-test
  (testing "Failed sync renders error variant"
    (let [result (#'sut/sync-status-fragment {:status :failed
                                            :synced 0
                                            :failed 2
                                            :failures [{:repo "a/b" :error "not found" :error-category :access}
                                                       {:repo "c/d" :error "timeout" :error-category :network}]})]
      (is (vector? result))
      ;; Should contain 'failed' somewhere
      (let [flat (flatten (tree-seq coll? seq result))]
        (is (some #(and (string? %) (str/includes? % "failed")) flat))))))

;; TODO: Tests for unimplemented badge/label helper functions.
;; Uncomment when error-category-badge-variant, error-category-label,
;; readiness-state-badge-variant, and readiness-state-label are added to fleet.clj.
(comment

(deftest error-category-badge-variant-test
  (testing "Maps error categories to badge variants"
    (is (= :error (#'sut/error-category-badge-variant :auth)))
    (is (= :error (#'sut/error-category-badge-variant :access)))
    (is (= :warning (#'sut/error-category-badge-variant :rate-limit)))
    (is (= :warning (#'sut/error-category-badge-variant :parse)))
    (is (= :warning (#'sut/error-category-badge-variant :network)))
    (is (= :neutral (#'sut/error-category-badge-variant :unknown)))
    (is (= :neutral (#'sut/error-category-badge-variant :something-else)))))

(deftest error-category-label-test
  (testing "Maps error categories to human-readable labels"
    (is (= "Auth" (#'sut/error-category-label :auth)))
    (is (= "Access" (#'sut/error-category-label :access)))
    (is (= "Rate Limit" (#'sut/error-category-label :rate-limit)))
    (is (= "Parse Error" (#'sut/error-category-label :parse)))
    (is (= "Network" (#'sut/error-category-label :network)))
    (is (= "Error" (#'sut/error-category-label :unknown)))
    (is (= "Error" (#'sut/error-category-label :something-else)))))

(deftest readiness-state-badge-variant-test
  (testing "Maps readiness states to badge variants"
    (is (= :success (#'sut/readiness-state-badge-variant :merge-ready)))
    (is (= :error (#'sut/readiness-state-badge-variant :ci-failing)))
    (is (= :warning (#'sut/readiness-state-badge-variant :changes-requested)))
    (is (= :warning (#'sut/readiness-state-badge-variant :merge-conflicts)))
    (is (= :warning (#'sut/readiness-state-badge-variant :policy-failing)))
    (is (= :neutral (#'sut/readiness-state-badge-variant :dep-blocked)))
    (is (= :info (#'sut/readiness-state-badge-variant :needs-review)))
    (is (= :neutral (#'sut/readiness-state-badge-variant :something-else)))))

(deftest readiness-state-label-test
  (testing "Maps readiness states to human-readable labels"
    (is (= "Ready" (#'sut/readiness-state-label :merge-ready)))
    (is (= "CI Failing" (#'sut/readiness-state-label :ci-failing)))
    (is (= "Changes Requested" (#'sut/readiness-state-label :changes-requested)))
    (is (= "Merge Conflicts" (#'sut/readiness-state-label :merge-conflicts)))
    (is (= "Policy Failing" (#'sut/readiness-state-label :policy-failing)))
    (is (= "Dep Blocked" (#'sut/readiness-state-label :dep-blocked)))
    (is (= "Needs Review" (#'sut/readiness-state-label :needs-review)))
    (is (= "Unknown" (#'sut/readiness-state-label :something-else)))))

) ;; end comment — unimplemented badge/label helpers

;; ============================================================================
;; train-list-fragment tests
;; ============================================================================

(deftest train-list-fragment-empty-test
  (testing "Empty trains renders empty state with emoji and actions"
    (let [html-str (str (sut/train-list-fragment []))]
      (is (str/includes? html-str "No PR Trains Yet"))
      (is (str/includes? html-str "🚂")))))

(deftest train-list-fragment-with-trains-test
  (testing "Trains render table with name, status, counts"
    (let [train-id (random-uuid)
          trains [{:train/id train-id
                   :train/name "Release 1.0"
                   :train/status :reviewing
                   :train/prs [{:pr/number 1 :pr/status :reviewing :pr/merge-order 1}
                               {:pr/number 2 :pr/status :merged :pr/merge-order 2}]
                   :train/ready-to-merge [2]
                   :train/blocking-prs [1]
                   :train/blocking-details [{:pr/number 1 :blocking/reasons ["CI failing"]}]
                   :train/readiness-summary {:reviewing 1 :merge-ready 1}}]
          html-str (str (sut/train-list-fragment trains))]
      (is (str/includes? html-str "Release 1.0"))
      (is (str/includes? html-str "reviewing"))
      (is (str/includes? html-str (str train-id))))))

;; ============================================================================
;; train-detail-view tests
;; ============================================================================

(deftest train-detail-view-error-test
  (testing "Error train renders error message"
    (let [layout (fn [title body] [:html [:head [:title title]] [:body body]])
          result (sut/train-detail-view layout {:error "Train not found"})]
      (is (some #(and (string? %) (str/includes? % "Train not found"))
               (flatten (tree-seq coll? seq result)))))))

(deftest train-detail-view-renders-prs-test
  (testing "Train detail renders PR cards with readiness info"
    (let [layout (fn [title body] [:html [:head [:title title]] [:body body]])
          train {:train/id (random-uuid)
                 :train/name "My Train"
                 :train/description "A test train"
                 :train/prs [{:pr/number 1
                              :pr/repo "acme/service"
                              :pr/title "Fix bug"
                              :pr/status :approved
                              :pr/ci-status :passed
                              :pr/merge-order 1
                              :pr/depends-on []
                              :pr/blocking-reasons []
                              :pr/readiness {:readiness/state :merge-ready
                                             :readiness/score 0.95}}]
                 :train/readiness-summary {:merge-ready 1}}
          result (sut/train-detail-view layout train)
          flat (flatten (tree-seq coll? seq result))]
      (is (some #(and (string? %) (str/includes? % "My Train")) flat))
      (is (some #(and (string? %) (str/includes? % "#1")) flat))
      (is (some #(and (string? %) (str/includes? % "Fix bug")) flat))
      (is (some #(and (string? %) (str/includes? % "Ready")) flat)))))

;; ============================================================================
;; format-sync-time tests
;; ============================================================================

(deftest format-sync-time-test
  (testing "Formats a Date into string"
    (let [result (#'sut/format-sync-time (java.util.Date.))]
      (is (string? result))
      (is (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}" result))))
  (testing "Returns nil for nil input"
    (is (nil? (#'sut/format-sync-time nil)))))

;; ============================================================================
;; fleet-action-onclick tests
;; ============================================================================

(deftest fleet-action-onclick-test
  (testing "Returns correct onclick handlers"
    (is (str/includes? (#'sut/fleet-action-onclick :add-repo) "addRepo"))
    (is (str/includes? (#'sut/fleet-action-onclick :discover-repos) "discoverRepos"))
    (is (str/includes? (#'sut/fleet-action-onclick :sync-prs) "syncPrs"))
    (is (str/includes? (#'sut/fleet-action-onclick :discover-sync) "discoverAndSync"))
    (is (= "" (#'sut/fleet-action-onclick :unknown)))))
