(ns ai.miniforge.pr-train.state-transitions-test
  "Tests for state transition validation."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.pr-train.state :as state]))

;; ============================================================================
;; State transition validation tests
;; ============================================================================

(deftest train-transitions-test
  (testing "train-transitions map is complete"
    (is (map? state/train-transitions))
    (is (contains? state/train-transitions :drafting))
    (is (contains? state/train-transitions :merged))
    (is (contains? state/train-transitions :abandoned))))

(deftest valid-train-transition?-test
  (testing "valid forward transitions"
    (is (state/valid-train-transition? :drafting :open))
    (is (state/valid-train-transition? :drafting :abandoned))
    (is (state/valid-train-transition? :open :reviewing))
    (is (state/valid-train-transition? :reviewing :merging))
    (is (state/valid-train-transition? :merging :merged))
    (is (state/valid-train-transition? :merging :failed))
    (is (state/valid-train-transition? :failed :rolled-back))
    (is (state/valid-train-transition? :failed :abandoned)))

  (testing "invalid transitions"
    (is (not (state/valid-train-transition? :merged :drafting)))
    (is (not (state/valid-train-transition? :abandoned :drafting)))
    (is (not (state/valid-train-transition? :rolled-back :open)))
    (is (not (state/valid-train-transition? :drafting :merged)))))

(deftest valid-pr-transition?-test
  (testing "valid PR transitions"
    (is (state/valid-pr-transition? :draft :open))
    (is (state/valid-pr-transition? :draft :closed))
    (is (state/valid-pr-transition? :open :reviewing))
    (is (state/valid-pr-transition? :reviewing :changes-requested))
    (is (state/valid-pr-transition? :reviewing :approved))
    (is (state/valid-pr-transition? :changes-requested :reviewing))
    (is (state/valid-pr-transition? :approved :merging))
    (is (state/valid-pr-transition? :merging :merged))
    (is (state/valid-pr-transition? :merging :failed))
    (is (state/valid-pr-transition? :closed :open)))

  (testing "invalid PR transitions"
    (is (not (state/valid-pr-transition? :draft :merged)))
    (is (not (state/valid-pr-transition? :merged :draft)))
    (is (not (state/valid-pr-transition? :open :merged)))
    (is (not (state/valid-pr-transition? :reviewing :merged)))))

(deftest terminal-status-tests
  (testing "terminal train statuses"
    (is (state/terminal-train-status? :merged))
    (is (state/terminal-train-status? :rolled-back))
    (is (state/terminal-train-status? :abandoned))
    (is (not (state/terminal-train-status? :drafting)))
    (is (not (state/terminal-train-status? :failed))))

  (testing "terminal PR statuses"
    (is (state/terminal-pr-status? :merged))
    (is (not (state/terminal-pr-status? :draft)))
    (is (not (state/terminal-pr-status? :failed)))))
