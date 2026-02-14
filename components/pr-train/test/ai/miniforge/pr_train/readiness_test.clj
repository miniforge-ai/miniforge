(ns ai.miniforge.pr-train.readiness-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.pr-train.readiness :as readiness]))

;; ============================================================================
;; Factor scoring tests
;; ============================================================================

(deftest score-deps-factor-test
  (testing "no dependencies scores 1.0"
    (let [train {:train/prs []}
          pr {:pr/depends-on []}]
      (is (= 1.0 (readiness/score-deps-factor train pr readiness/default-config)))))

  (testing "all deps merged scores 1.0"
    (let [train {:train/prs [{:pr/number 1 :pr/status :merged}
                              {:pr/number 2 :pr/status :merged}]}
          pr {:pr/depends-on [1 2]}]
      (is (= 1.0 (readiness/score-deps-factor train pr readiness/default-config)))))

  (testing "no deps merged scores 0.0"
    (let [train {:train/prs [{:pr/number 1 :pr/status :open}
                              {:pr/number 2 :pr/status :open}]}
          pr {:pr/depends-on [1 2]}]
      (is (= 0.0 (readiness/score-deps-factor train pr readiness/default-config)))))

  (testing "half deps merged scores 0.5"
    (let [train {:train/prs [{:pr/number 1 :pr/status :merged}
                              {:pr/number 2 :pr/status :open}]}
          pr {:pr/depends-on [1 2]}]
      (is (= 0.5 (readiness/score-deps-factor train pr readiness/default-config))))))

(deftest score-ci-factor-test
  (testing "passed CI scores 1.0"
    (is (= 1.0 (readiness/score-ci-factor nil {:pr/ci-status :passed} readiness/default-config))))

  (testing "running CI scores 0.5"
    (is (= 0.5 (readiness/score-ci-factor nil {:pr/ci-status :running} readiness/default-config))))

  (testing "failed CI scores 0.0"
    (is (= 0.0 (readiness/score-ci-factor nil {:pr/ci-status :failed} readiness/default-config)))))

(deftest score-approved-factor-test
  (testing "approved PR scores 1.0"
    (is (= 1.0 (readiness/score-approved-factor nil {:pr/status :approved} readiness/default-config))))

  (testing "reviewing PR scores 0.5"
    (is (= 0.5 (readiness/score-approved-factor nil {:pr/status :reviewing} readiness/default-config))))

  (testing "draft PR scores 0.0"
    (is (= 0.0 (readiness/score-approved-factor nil {:pr/status :draft} readiness/default-config)))))

(deftest score-gates-factor-test
  (testing "no gates scores 1.0"
    (is (= 1.0 (readiness/score-gates-factor nil {:pr/gate-results []} readiness/default-config))))

  (testing "all gates passed scores 1.0"
    (is (= 1.0 (readiness/score-gates-factor nil
                 {:pr/gate-results [{:gate/passed? true} {:gate/passed? true}]}
                 readiness/default-config))))

  (testing "half gates passed scores 0.5"
    (is (= 0.5 (readiness/score-gates-factor nil
                 {:pr/gate-results [{:gate/passed? true} {:gate/passed? false}]}
                 readiness/default-config)))))

(deftest score-age-factor-test
  (testing "brand new PR scores 1.0"
    (is (= 1.0 (readiness/score-age-factor nil {:pr/derived-state {:age-days 0}} readiness/default-config))))

  (testing "max age PR scores 0.0"
    (is (= 0.0 (readiness/score-age-factor nil {:pr/derived-state {:age-days 14}} readiness/default-config)))))

(deftest score-staleness-factor-test
  (testing "just updated PR scores 1.0"
    (is (= 1.0 (readiness/score-staleness-factor nil {:pr/derived-state {:staleness-hours 0}} readiness/default-config))))

  (testing "max stale PR scores 0.0"
    (is (= 0.0 (readiness/score-staleness-factor nil {:pr/derived-state {:staleness-hours 72}} readiness/default-config)))))

;; ============================================================================
;; Weighted aggregation tests
;; ============================================================================

(deftest compute-readiness-score-test
  (testing "perfect PR scores near 1.0"
    (let [train {:train/prs [{:pr/number 1 :pr/status :merged}]}
          pr {:pr/number 2
              :pr/depends-on [1]
              :pr/ci-status :passed
              :pr/status :approved
              :pr/gate-results [{:gate/passed? true}]
              :pr/derived-state {:age-days 0 :staleness-hours 0}}
          score (readiness/compute-readiness-score train pr)]
      (is (>= score 0.95))
      (is (<= score 1.0))))

  (testing "terrible PR scores near 0.0"
    (let [train {:train/prs [{:pr/number 1 :pr/status :open}]}
          pr {:pr/number 2
              :pr/depends-on [1]
              :pr/ci-status :failed
              :pr/status :draft
              :pr/gate-results [{:gate/passed? false}]
              :pr/derived-state {:age-days 14 :staleness-hours 72}}
          score (readiness/compute-readiness-score train pr)]
      (is (<= score 0.1)))))

;; ============================================================================
;; Explainability tests
;; ============================================================================

(deftest explain-readiness-test
  (testing "explain returns all factors"
    (let [train {:train/prs []}
          pr {:pr/depends-on [] :pr/ci-status :passed :pr/status :approved
              :pr/gate-results [] :pr/derived-state {:age-days 0 :staleness-hours 0}}
          result (readiness/explain-readiness train pr)]
      (is (contains? result :readiness/score))
      (is (contains? result :readiness/threshold))
      (is (contains? result :readiness/ready?))
      (is (= 6 (count (:readiness/factors result))))

      (testing "factors have required keys"
        (doseq [f (:readiness/factors result)]
          (is (contains? f :factor))
          (is (contains? f :weight))
          (is (contains? f :score))
          (is (contains? f :contribution))))))

  (testing "score matches explicit computation"
    (let [train {:train/prs []}
          pr {:pr/depends-on [] :pr/ci-status :passed :pr/status :approved
              :pr/gate-results [] :pr/derived-state {:age-days 0 :staleness-hours 0}}
          explained (readiness/explain-readiness train pr)
          computed (readiness/compute-readiness-score train pr)]
      (is (< (Math/abs (- (:readiness/score explained) computed)) 0.001)))))

;; ============================================================================
;; Threshold tests
;; ============================================================================

(deftest threshold-test
  (testing "ready? reflects threshold comparison"
    (let [train {:train/prs []}
          good-pr {:pr/depends-on [] :pr/ci-status :passed :pr/status :approved
                   :pr/gate-results [{:gate/passed? true}]
                   :pr/derived-state {:age-days 0 :staleness-hours 0}}
          bad-pr {:pr/depends-on [] :pr/ci-status :failed :pr/status :draft
                  :pr/gate-results [{:gate/passed? false}]
                  :pr/derived-state {:age-days 14 :staleness-hours 72}}]
      (is (true? (:readiness/ready? (readiness/explain-readiness train good-pr))))
      (is (false? (:readiness/ready? (readiness/explain-readiness train bad-pr)))))))
