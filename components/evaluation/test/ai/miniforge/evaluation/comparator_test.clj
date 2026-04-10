;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.evaluation.comparator-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evaluation.interface :as eval]))

(deftest compare-results-test
  (testing "clearly better candidate recommends promote"
    (let [baseline  (concat (repeat 10 0.65) (repeat 10 0.75))
          candidate (concat (repeat 10 0.85) (repeat 10 0.95))
          result (eval/compare-results baseline candidate)]
      (is (:significant? result))
      (is (pos? (:lift result)))
      (is (= :promote (:recommendation result)))))

  (testing "clearly worse candidate recommends reject"
    (let [baseline  (concat (repeat 10 0.85) (repeat 10 0.95))
          candidate (concat (repeat 10 0.45) (repeat 10 0.55))
          result (eval/compare-results baseline candidate)]
      (is (:significant? result))
      (is (neg? (:lift result)))
      (is (= :reject (:recommendation result)))))

  (testing "insufficient data recommends needs-more-data"
    (let [baseline [0.8 0.9]
          candidate [0.85 0.95]
          result (eval/compare-results baseline candidate)]
      (is (= :needs-more-data (:recommendation result)))))

  (testing "identical distributions are not significant"
    (let [vals (repeat 20 0.8)
          result (eval/compare-results vals vals)]
      (is (not (:significant? result))))))

(deftest golden-set-test
  (let [gs (eval/create-golden-set {:id "test-set" :version "1.0.0"})
        gs (-> gs
               (eval/add-entry {:entry/id "e1"
                                 :entry/input {:task "write tests"}
                                 :entry/expected-outcome {:tests-pass true}
                                 :entry/pass-criteria [:has-tests :tests-pass]})
               (eval/add-entry {:entry/id "e2"
                                 :entry/input {:task "fix bug"}
                                 :entry/expected-outcome {:bug-fixed true}
                                 :entry/pass-criteria [:no-regression]}))]

    (testing "entry count"
      (is (= 2 (eval/entry-count gs))))

    (testing "run golden set with all passing"
      (let [result (eval/run-golden-set gs
                                         (fn [_input] {:tests-pass true :has-tests true})
                                         (fn [_criterion _actual] true))]
        (is (= 2 (:total result)))
        (is (= 2 (:passed result)))
        (is (= 1.0 (:pass-rate result)))))

    (testing "run golden set with some failing"
      (let [result (eval/run-golden-set gs
                                         (fn [_input] {})
                                         (fn [criterion _actual]
                                           (= criterion :no-regression)))]
        (is (= 2 (:total result)))
        (is (= 1 (:passed result)))
        (is (= 0.5 (:pass-rate result)))))))
