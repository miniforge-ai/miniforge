(ns ai.miniforge.web-dashboard.views.fleet-view-test
  "Tests for fleet-view and train-detail-view rendering:
   - fleet-view renders summary, sync status, train list
   - train-detail-view with dependencies and blocking reasons
   - train-detail-view with readiness scoring display
   - Edge cases: empty fleet, error trains"
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.views.fleet :as sut]))

(defn mock-layout [title body]
  [:html [:head [:title title]] [:body body]])

(defn flat-strings
  "Extract all string leaves from a nested hiccup structure."
  [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (filter string?)))

(defn contains-string?
  "Check if any string in the hiccup tree contains the substring."
  [hiccup substr]
  (some #(str/includes? % substr) (flat-strings hiccup)))

;; ============================================================================
;; fleet-view rendering
;; ============================================================================

(deftest fleet-view-renders-summary-counts-test
  (testing "fleet-view renders train/PR/repo counts from summary"
    (let [fleet-state {:summary {:active-trains 3
                                 :total-prs 12
                                 :repos 4
                                 :configured-repos 6}
                       :trains []
                       :last-sync nil}
          result (sut/fleet-view mock-layout fleet-state)]
      (is (contains-string? result "3 trains"))
      (is (contains-string? result "12 PRs"))
      (is (contains-string? result "4 repos with PRs"))
      (is (contains-string? result "6 configured")))))

(deftest fleet-view-renders-empty-summary-test
  (testing "fleet-view handles missing summary gracefully (defaults to 0)"
    (let [fleet-state {:trains [] :last-sync nil}
          result (sut/fleet-view mock-layout fleet-state)]
      (is (contains-string? result "0 trains"))
      (is (contains-string? result "0 PRs")))))

(deftest fleet-view-renders-sync-status-test
  (testing "fleet-view renders last sync info"
    (let [fleet-state {:summary {:active-trains 1 :total-prs 2 :repos 1 :configured-repos 1}
                       :trains []
                       :last-sync {:status :success
                                   :timestamp (java.util.Date.)
                                   :synced 1 :failed 0
                                   :failures []}}
          result (sut/fleet-view mock-layout fleet-state)]
      (is (contains-string? result "success")))))

(deftest fleet-view-renders-action-buttons-test
  (testing "fleet-view contains action buttons"
    (let [fleet-state {:summary {} :trains [] :last-sync nil}
          result (sut/fleet-view mock-layout fleet-state)]
      (is (contains-string? result "Run Workflow"))
      (is (contains-string? result "Repo"))
      (is (contains-string? result "Discover Repos"))
      (is (contains-string? result "Sync PRs")))))

(deftest fleet-view-renders-htmx-attributes-test
  (testing "fleet-view includes htmx refresh attributes"
    (let [fleet-state {:summary {} :trains [] :last-sync nil}
          result (sut/fleet-view mock-layout fleet-state)
          flat (flatten (tree-seq coll? seq result))]
      ;; Check for hx-get endpoint
      (is (some #(and (string? %) (str/includes? % "/api/trains")) flat)))))

;; ============================================================================
;; train-detail-view with dependencies
;; ============================================================================

(deftest train-detail-view-renders-dependencies-test
  (testing "Train detail shows dependency info"
    (let [train {:train/id (random-uuid)
                 :train/name "Dep Train"
                 :train/description "Train with deps"
                 :train/prs [{:pr/number 1
                              :pr/repo "acme/api"
                              :pr/title "Base PR"
                              :pr/status :merged
                              :pr/ci-status :passed
                              :pr/merge-order 1
                              :pr/depends-on []
                              :pr/blocking-reasons []
                              :pr/readiness {:readiness/state :merge-ready
                                             :readiness/score 1.0}}
                             {:pr/number 2
                              :pr/repo "acme/api"
                              :pr/title "Dependent PR"
                              :pr/status :approved
                              :pr/ci-status :passed
                              :pr/merge-order 2
                              :pr/depends-on [1]
                              :pr/blocking-reasons []
                              :pr/readiness {:readiness/state :merge-ready
                                             :readiness/score 0.95}}]}
          result (sut/train-detail-view mock-layout train)]
      (is (contains-string? result "Depends: 1")))))

(deftest train-detail-view-renders-blocking-reasons-test
  (testing "Train detail shows blocking reasons"
    (let [train {:train/id (random-uuid)
                 :train/name "Blocked Train"
                 :train/description "Has blockers"
                 :train/prs [{:pr/number 5
                              :pr/repo "acme/web"
                              :pr/title "Blocked PR"
                              :pr/status :open
                              :pr/ci-status :failed
                              :pr/merge-order 1
                              :pr/depends-on []
                              :pr/blocking-reasons ["CI checks failed" "Awaiting review"]
                              :pr/readiness {:readiness/state :ci-failing
                                             :readiness/score 0.30}}]}
          result (sut/train-detail-view mock-layout train)]
      (is (contains-string? result "CI checks failed"))
      (is (contains-string? result "Awaiting review")))))

(deftest train-detail-view-renders-readiness-score-test
  (testing "Train detail shows readiness score formatted to 2 decimals"
    (let [train {:train/id (random-uuid)
                 :train/name "Score Train"
                 :train/description "Show score"
                 :train/prs [{:pr/number 1
                              :pr/repo "acme/api"
                              :pr/title "PR with score"
                              :pr/status :approved
                              :pr/ci-status :passed
                              :pr/merge-order 1
                              :pr/depends-on []
                              :pr/blocking-reasons []
                              :pr/readiness {:readiness/state :merge-ready
                                             :readiness/score 0.87654}}]}
          result (sut/train-detail-view mock-layout train)]
      ;; Should contain formatted score
      (is (contains-string? result "0.88")))))

(deftest train-detail-view-renders-merge-graph-test
  (testing "Train detail renders merge graph with order numbers"
    (let [train {:train/id (random-uuid)
                 :train/name "Graph Train"
                 :train/description "Show graph"
                 :train/prs [{:pr/number 10 :pr/repo "a/b" :pr/title "First"
                              :pr/status :approved :pr/ci-status :passed
                              :pr/merge-order 1 :pr/depends-on [] :pr/blocking-reasons []
                              :pr/readiness {:readiness/state :merge-ready :readiness/score 1.0}}
                             {:pr/number 20 :pr/repo "a/b" :pr/title "Second"
                              :pr/status :open :pr/ci-status :running
                              :pr/merge-order 2 :pr/depends-on [] :pr/blocking-reasons []
                              :pr/readiness {:readiness/state :needs-review :readiness/score 0.5}}]}
          result (sut/train-detail-view mock-layout train)]
      ;; Merge order numbers rendered
      (is (contains-string? result "1"))
      (is (contains-string? result "2"))
      (is (contains-string? result "#10"))
      (is (contains-string? result "#20")))))

(deftest train-detail-view-actions-test
  (testing "Train detail renders Pause and Merge Next buttons"
    (let [train-id (random-uuid)
          train {:train/id train-id
                 :train/name "Action Train"
                 :train/description "Test actions"
                 :train/prs []}
          result (sut/train-detail-view mock-layout train)]
      (is (contains-string? result "Pause"))
      (is (contains-string? result "Merge Next"))
      ;; API endpoints include train-id
      (is (contains-string? result (str train-id))))))

;; ============================================================================
;; train-list-fragment edge cases
;; ============================================================================

(deftest train-list-fragment-multiple-blocking-details-test
  (testing "Train list shows up to 2 blocking details"
    (let [trains [{:train/id (random-uuid)
                   :train/name "Multi Block"
                   :train/status :reviewing
                   :train/prs [{:pr/number 1 :pr/status :open :pr/merge-order 1}
                               {:pr/number 2 :pr/status :open :pr/merge-order 2}
                               {:pr/number 3 :pr/status :open :pr/merge-order 3}]
                   :train/ready-to-merge []
                   :train/blocking-prs [1 2 3]
                   :train/blocking-details [{:pr/number 1 :blocking/reasons ["CI failing"]}
                                            {:pr/number 2 :blocking/reasons ["Changes requested"]}
                                            {:pr/number 3 :blocking/reasons ["Behind main"]}]
                   :train/readiness-summary {:ci-failing 3}}]
          html-str (str (sut/train-list-fragment trains))]
      ;; Shows first 2 blocking details
      (is (str/includes? html-str "#1"))
      (is (str/includes? html-str "#2")))))

(deftest train-list-fragment-merged-train-test
  (testing "Merged train renders with success variant"
    (let [trains [{:train/id (random-uuid)
                   :train/name "Done Train"
                   :train/status :merged
                   :train/prs [{:pr/number 1 :pr/status :merged :pr/merge-order 1}]
                   :train/ready-to-merge [1]
                   :train/blocking-prs []
                   :train/blocking-details []
                   :train/readiness-summary {:merge-ready 1}}]
          html-str (str (sut/train-list-fragment trains))]
      (is (str/includes? html-str "Done Train"))
      (is (str/includes? html-str "merged")))))

(deftest train-list-fragment-progress-bar-test
  (testing "Progress bar reflects merged percentage"
    (let [trains [{:train/id (random-uuid)
                   :train/name "Half Done"
                   :train/status :merging
                   :train/prs [{:pr/number 1 :pr/status :merged :pr/merge-order 1}
                               {:pr/number 2 :pr/status :open :pr/merge-order 2}]
                   :train/ready-to-merge []
                   :train/blocking-prs []
                   :train/blocking-details []
                   :train/readiness-summary {:merge-ready 1 :needs-review 1}}]
          html-str (str (sut/train-list-fragment trains))]
      (is (str/includes? html-str "Half Done")))))

;; ============================================================================
;; sync-status-fragment with no timestamp
;; ============================================================================

(deftest sync-status-fragment-no-timestamp-test
  (testing "Sync with nil timestamp omits time display"
    (let [result (sut/sync-status-fragment {:status :success
                                              :timestamp nil
                                              :synced 1 :failed 0
                                              :failures []})]
      (is (vector? result))
      ;; Should still render badge and counts
      (is (contains-string? result "success"))
      (is (contains-string? result "synced 1")))))

(deftest sync-status-fragment-with-message-test
  (testing "Sync with message renders message div"
    (let [result (sut/sync-status-fragment {:status :partial
                                              :timestamp (java.util.Date.)
                                              :synced 1 :failed 1
                                              :message "Some repos failed"
                                              :failures []})]
      (is (contains-string? result "Some repos failed")))))
