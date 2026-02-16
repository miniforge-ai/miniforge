(ns ai.miniforge.pr-train.tiers-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.pr-train.tiers :as tiers]))

;; ============================================================================
;; Tier constraint tests
;; ============================================================================

(deftest tier-0-test
  (testing "tier-0 disallows all automation"
    (is (false? (tiers/tier-allows? :tier-0 :auto-approve 1.0 {:risk/level :low})))
    (is (false? (tiers/tier-allows? :tier-0 :auto-merge 1.0 {:risk/level :low})))))

(deftest tier-1-test
  (testing "tier-1 allows auto-merge but not auto-approve"
    (is (false? (tiers/tier-allows? :tier-1 :auto-approve 1.0 {:risk/level :low})))
    (is (true? (tiers/tier-allows? :tier-1 :auto-merge 0.5 {:risk/level :low})))))

(deftest tier-2-test
  (testing "tier-2 allows both when constraints met"
    (is (true? (tiers/tier-allows? :tier-2 :auto-approve 0.95 {:risk/level :low})))
    (is (true? (tiers/tier-allows? :tier-2 :auto-merge 0.95 {:risk/level :medium}))))

  (testing "tier-2 rejects when readiness too low"
    (is (false? (tiers/tier-allows? :tier-2 :auto-approve 0.80 {:risk/level :low}))))

  (testing "tier-2 rejects when risk too high"
    (is (false? (tiers/tier-allows? :tier-2 :auto-approve 0.95 {:risk/level :high})))
    (is (false? (tiers/tier-allows? :tier-2 :auto-merge 0.95 {:risk/level :critical})))))

(deftest tier-3-test
  (testing "tier-3 allows with relaxed constraints"
    (is (true? (tiers/tier-allows? :tier-3 :auto-approve 0.80 {:risk/level :high})))
    (is (true? (tiers/tier-allows? :tier-3 :auto-merge 0.80 {:risk/level :medium}))))

  (testing "tier-3 rejects critical risk"
    (is (false? (tiers/tier-allows? :tier-3 :auto-approve 0.80 {:risk/level :critical}))))

  (testing "tier-3 rejects low readiness"
    (is (false? (tiers/tier-allows? :tier-3 :auto-approve 0.70 {:risk/level :low})))))

;; ============================================================================
;; Operation gating tests
;; ============================================================================

(deftest can-auto-approve-test
  (testing "convenience function matches tier-allows?"
    (is (= (tiers/tier-allows? :tier-2 :auto-approve 0.95 {:risk/level :low})
           (tiers/can-auto-approve? :tier-2 0.95 {:risk/level :low})))
    (is (= (tiers/tier-allows? :tier-0 :auto-approve 1.0 {:risk/level :low})
           (tiers/can-auto-approve? :tier-0 1.0 {:risk/level :low})))))

(deftest can-auto-merge-test
  (testing "convenience function matches tier-allows?"
    (is (= (tiers/tier-allows? :tier-1 :auto-merge 0.5 {:risk/level :low})
           (tiers/can-auto-merge? :tier-1 0.5 {:risk/level :low})))
    (is (= (tiers/tier-allows? :tier-0 :auto-merge 1.0 {:risk/level :low})
           (tiers/can-auto-merge? :tier-0 1.0 {:risk/level :low})))))

;; ============================================================================
;; Config lookup tests
;; ============================================================================

(deftest get-repo-tier-test
  (testing "returns configured tier"
    (is (= :tier-2
           (tiers/get-repo-tier {"acme/terraform" {:automation-tier :tier-2}} "acme/terraform"))))

  (testing "defaults to tier-1 for unknown repos"
    (is (= :tier-1 (tiers/get-repo-tier {} "unknown/repo")))))

(deftest get-automation-tier-test
  (testing "returns tier with definition"
    (let [result (tiers/get-automation-tier
                  {"acme/repo" {:automation-tier :tier-2}} "acme/repo")]
      (is (= :tier-2 (:tier result)))
      (is (map? (:definition result)))
      (is (true? (get-in result [:definition :auto-approve?]))))))

;; ============================================================================
;; Unknown operation test
;; ============================================================================

(deftest unknown-operation-test
  (testing "unknown operations return false"
    (is (false? (tiers/tier-allows? :tier-3 :unknown-op 1.0 {:risk/level :low})))))
