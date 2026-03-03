(ns ai.miniforge.web-dashboard.state.fleet-test
  "Unit tests for fleet state: risk scoring, stats computation, and risk analysis."
  (:require
   [clojure.test :refer [deftest testing is are]]))

;; ============================================================================
;; Test data fixtures
;; ============================================================================

(def healthy-train
  {:train/id :train-1
   :train/name "feature-auth"
   :train/status :open
   :train/prs [{:pr/number 101 :pr/status :approved :pr/ci-status :passed :pr/repo "org/repo-a"}
               {:pr/number 102 :pr/status :approved :pr/ci-status :passed :pr/repo "org/repo-a"}]
   :train/ready-to-merge [{:pr/number 101}]
   :train/blocking-prs []
   :train/updated-at "2026-03-01T10:00:00Z"})

(def risky-train
  {:train/id :train-2
   :train/name "feature-payments"
   :train/status :reviewing
   :train/prs [{:pr/number 201 :pr/status :changes-requested :pr/ci-status :failed :pr/repo "org/repo-b"}
               {:pr/number 202 :pr/status :reviewing :pr/ci-status :running :pr/repo "org/repo-b"}
               {:pr/number 203 :pr/status :approved :pr/ci-status :passed :pr/repo "org/repo-b"}]
   :pr/depends-on [:dep-1 :dep-2]
   :train/ready-to-merge []
   :train/blocking-prs [{:pr/number 201}]
   :train/updated-at "2026-03-01T11:00:00Z"})

(def critical-train
  {:train/id :train-3
   :train/name "feature-migration"
   :train/status :merging
   :train/prs [{:pr/number 301 :pr/status :approved :pr/ci-status :failed :pr/repo "org/repo-c"}
               {:pr/number 302 :pr/status :changes-requested :pr/ci-status :failed :pr/repo "org/repo-c"}
               {:pr/number 303 :pr/status :reviewing :pr/ci-status :pending :pr/repo "org/repo-c"}
               {:pr/number 304 :pr/status :approved :pr/ci-status :passed :pr/repo "org/repo-c"}
               {:pr/number 305 :pr/status :approved :pr/ci-status :passed :pr/repo "org/repo-c"}
               {:pr/number 306 :pr/status :approved :pr/ci-status :passed :pr/repo "org/repo-c"}]
   :pr/depends-on [:dep-a :dep-b :dep-c]
   :train/ready-to-merge [{:pr/number 304}]
   :train/blocking-prs [{:pr/number 301} {:pr/number 302} {:pr/number 303}]
   :train/updated-at "2026-03-01T09:00:00Z"})

(def empty-train
  {:train/id :train-empty
   :train/name "empty-train"
   :train/status :open
   :train/prs []
   :train/ready-to-merge []
   :train/blocking-prs []})

;; We require the SUT after defining fixtures to keep ns require clean
(require '[ai.miniforge.web-dashboard.state.fleet :as sut])

;; ============================================================================
;; calculate-risk-score tests
;; ============================================================================

(deftest calculate-risk-score-zero-baseline-test
  (testing "Entity with no risk factors scores 0"
    (is (= 0 (sut/calculate-risk-score
              {:pr/ci-status :passed
               :pr/status :approved})))))

(deftest calculate-risk-score-ci-penalties-test
  (testing "CI status penalties"
    (are [ci-status expected]
        (= expected (sut/calculate-risk-score {:pr/ci-status ci-status}))
      :failed  30
      :running 5
      :pending 10
      :passed  0)))

(deftest calculate-risk-score-status-penalties-test
  (testing "PR/train status penalties"
    (are [status expected]
        (= expected (sut/calculate-risk-score {:pr/status status}))
      :changes-requested 15
      :reviewing         5
      :merging           10
      :approved          0)))

(deftest calculate-risk-score-dependency-penalty-test
  (testing "Dependencies add 3 points each"
    (is (= 0  (sut/calculate-risk-score {:pr/depends-on []})))
    (is (= 3  (sut/calculate-risk-score {:pr/depends-on [:a]})))
    (is (= 9  (sut/calculate-risk-score {:pr/depends-on [:a :b :c]})))
    (is (= 15 (sut/calculate-risk-score {:pr/depends-on [:a :b :c :d :e]})))))

(deftest calculate-risk-score-blocking-penalty-test
  (testing "Blocking PRs add 5 points each"
    (is (= 5  (sut/calculate-risk-score {:train/blocking-prs [{:pr/number 1}]})))
    (is (= 15 (sut/calculate-risk-score {:train/blocking-prs [{:pr/number 1}
                                                               {:pr/number 2}
                                                               {:pr/number 3}]})))))

(deftest calculate-risk-score-combined-factors-test
  (testing "Multiple factors combine additively"
    (let [entity {:pr/ci-status :failed          ;; +30
                  :pr/status :changes-requested   ;; +15
                  :pr/depends-on [:a :b]          ;; +6
                  :train/blocking-prs [{:p 1}]}]  ;; +5
      (is (= 56 (sut/calculate-risk-score entity))))))

(deftest calculate-risk-score-capped-at-100-test
  (testing "Score is capped at 100"
    (let [entity {:pr/ci-status :failed                          ;; +30
                  :pr/status :changes-requested                   ;; +15
                  :pr/depends-on (vec (range 10))                ;; +30
                  :train/blocking-prs (repeat 10 {:pr/number 1})}] ;; +50
      (is (= 100 (sut/calculate-risk-score entity))))))

(deftest calculate-risk-score-nil-entity-test
  (testing "Empty/nil entity scores 0 (all defaults to zero)"
    (is (= 0 (sut/calculate-risk-score {})))
    (is (= 0 (sut/calculate-risk-score nil)))))

(deftest calculate-risk-score-alternate-key-forms-test
  (testing "Supports :ci-status as fallback for :pr/ci-status"
    (is (= 30 (sut/calculate-risk-score {:ci-status :failed}))))
  (testing "Supports :train/status as fallback for :pr/status"
    (is (= 10 (sut/calculate-risk-score {:train/status :merging})))))

;; ============================================================================
;; compute-stats tests
;; ============================================================================

(deftest compute-stats-empty-inputs-test
  (testing "Empty trains and workflows return zeroed stats"
    (let [stats (sut/compute-stats [] [])]
      (is (= 0 (get-in stats [:trains :total])))
      (is (= 0 (get-in stats [:trains :active])))
      (is (= 0 (get-in stats [:prs :total])))
      (is (= 0 (get-in stats [:prs :ready])))
      (is (= 0 (get-in stats [:prs :blocked])))
      (is (= 0 (get-in stats [:health :healthy])))
      (is (= 0 (get-in stats [:health :warning])))
      (is (= 0 (get-in stats [:health :critical])))
      (is (= 0 (get-in stats [:workflows :total])))
      (is (= 0 (get-in stats [:workflows :running])))
      (is (= 0 (get-in stats [:workflows :completed]))))))

(deftest compute-stats-single-healthy-train-test
  (testing "Single healthy train computes correct stats"
    (let [stats (sut/compute-stats [healthy-train] [])]
      (is (= 1 (get-in stats [:trains :total])))
      (is (= 1 (get-in stats [:trains :active]))
          "Open trains are active")
      (is (= 2 (get-in stats [:prs :total])))
      (is (= 1 (get-in stats [:prs :ready])))
      (is (= 0 (get-in stats [:prs :blocked])))
      (is (= 1 (get-in stats [:health :healthy]))))))

(deftest compute-stats-mixed-trains-test
  (testing "Mixed trains aggregate correctly"
    (let [stats (sut/compute-stats [healthy-train risky-train critical-train] [])]
      (is (= 3 (get-in stats [:trains :total])))
      ;; open + reviewing + merging are all active
      (is (= 3 (get-in stats [:trains :active])))
      ;; 2 + 3 + 6 = 11 total PRs
      (is (= 11 (get-in stats [:prs :total])))
      ;; 1 + 0 + 1 = 2 ready
      (is (= 2 (get-in stats [:prs :ready])))
      ;; 0 + 1 + 3 = 4 blocked
      (is (= 4 (get-in stats [:prs :blocked]))))))

(deftest compute-stats-workflow-counts-test
  (testing "Workflow status counts are correct"
    (let [wfs [{:status :running}
               {:status :running}
               {:status :completed}
               {:status :failed}
               {:status :pending}]
          stats (sut/compute-stats [] wfs)]
      (is (= 5 (get-in stats [:workflows :total])))
      (is (= 2 (get-in stats [:workflows :running])))
      (is (= 1 (get-in stats [:workflows :completed]))))))

(deftest compute-stats-health-buckets-test
  (testing "Health buckets classify risk scores correctly"
    ;; healthy-train=0, risky-train=16 (both < 20 = healthy), critical-train=34 (20-50 = warning)
    (let [stats (sut/compute-stats [healthy-train risky-train critical-train] [])]
      (is (= 2 (get-in stats [:health :healthy])))
      (is (= 1 (get-in stats [:health :warning])))
      (is (= 0 (get-in stats [:health :critical]))))))

;; ============================================================================
;; compute-risk-analysis tests
;; ============================================================================

(deftest compute-risk-analysis-empty-test
  (testing "Empty trains produce empty analysis"
    (let [analysis (sut/compute-risk-analysis [])]
      (is (empty? (:risks analysis)))
      (is (= {:high 0 :medium 0 :low 0} (:summary analysis))))))

(deftest compute-risk-analysis-levels-test
  (testing "Risk levels are correctly assigned"
    (let [analysis (sut/compute-risk-analysis [healthy-train risky-train critical-train])
          risks (:risks analysis)]
      ;; Sorted by score descending: critical-train(34), risky-train(16), healthy-train(0)
      (is (= :train-3 (:train-id (first risks)))
          "Highest risk train should be first")
      (is (= :medium (:risk-level (first risks))))
      (is (= :low (:risk-level (last risks)))))))

(deftest compute-risk-analysis-summary-counts-test
  (testing "Summary counts match risk level assignments"
    (let [analysis (sut/compute-risk-analysis [healthy-train risky-train critical-train])
          summary (:summary analysis)]
      (is (= 0 (:high summary)))
      (is (= 1 (:medium summary)))
      (is (= 2 (:low summary))))))

(deftest compute-risk-analysis-factors-test
  (testing "Risk factors are detected correctly"
    (let [analysis (sut/compute-risk-analysis [critical-train])
          factors (-> analysis :risks first :factors)
          factor-types (set (map :type factors))]
      (is (contains? factor-types :blocking-prs)
          "Should detect blocking PRs")
      (is (contains? factor-types :ci-failures)
          "Should detect CI failures")
      (is (contains? factor-types :large-train)
          "Should detect large trains (>5 PRs)"))))

(deftest compute-risk-analysis-no-factors-for-healthy-test
  (testing "Healthy trains have no risk factors"
    (let [analysis (sut/compute-risk-analysis [healthy-train])
          factors (-> analysis :risks first :factors)]
      (is (empty? factors)))))

(deftest compute-risk-analysis-sorted-descending-test
  (testing "Risks are sorted by score descending"
    (let [analysis (sut/compute-risk-analysis [healthy-train risky-train critical-train])
          scores (map :risk-score (:risks analysis))]
      (is (= scores (sort > scores))))))
