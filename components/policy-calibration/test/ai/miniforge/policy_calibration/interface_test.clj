(ns ai.miniforge.policy-calibration.interface-test
  (:require [ai.miniforge.policy-calibration.interface :as sut]
            [clojure.test :refer [deftest is testing]]))

(def ^:private bar {:max-clean-fp 0 :min-recall 0.8})

(defn- cell-at [cells] (fn [rel t] (get cells [rel t])))

(deftest score-rule-clean-and-violating-test
  (testing "0 false-positives + full recall -> gate-ready"
    (let [rule     {:rule/id :r/x}
          fixtures [{:rel "clean.clj" :seeded #{}} {:rel "viol.clj" :seeded #{:r/x}}]
          cells    {["clean.clj" 0] (sut/cell-success #{} {})
                    ["viol.clj" 0]  (sut/cell-success #{:r/x} {:r/x "bad"})}
          score    (sut/score-rule rule fixtures 1 bar (cell-at cells))]
      (is (= 0 (:clean-fp score)))
      (is (= 1.0 (:recall score)))
      (is (:gate-ready? score)))))

(deftest score-rule-false-positive-not-ready-test
  (testing "a false-fire on a clean fixture blocks gate-ready and reports the judge's reason"
    (let [rule     {:rule/id :r/x}
          fixtures [{:rel "clean.clj" :seeded #{}} {:rel "viol.clj" :seeded #{:r/x}}]
          cells    {["clean.clj" 0] (sut/cell-success #{:r/x} {:r/x "false fire"})
                    ["viol.clj" 0]  (sut/cell-success #{:r/x} {:r/x "bad"})}
          score    (sut/score-rule rule fixtures 1 bar (cell-at cells))]
      (is (= 1 (:clean-fp score)))
      (is (not (:gate-ready? score)))
      (is (= "false fire" (:judge-said (first (:fp-cases score))))))))

(deftest score-rule-anomaly-cells-not-counted-test
  (testing "backend/format-error cells are neither clean passes nor recall misses; with no successful eval a rule can't be certified"
    (let [rule     {:rule/id :r/x}
          fixtures [{:rel "clean.clj" :seeded #{}} {:rel "viol.clj" :seeded #{:r/x}}]
          cells    {["clean.clj" 0] (sut/backend-error "judge down" {})
                    ["viol.clj" 0]  (sut/format-error "unparseable" {})}
          score    (sut/score-rule rule fixtures 1 bar (cell-at cells))]
      (is (= 0 (:clean-fp score)) "an errored clean cell is not a false positive")
      (is (= 2 (:failed score)))
      (is (not (:evaluated? score)) "no successful evaluation -> not certifiable")
      (is (not (:gate-ready? score))))))

(deftest aggregate-consensus-test
  (testing "gate-ready only if EVERY run passes; a flip is unstable and not ready"
    (let [ready {:clean-fp 0 :recall 1.0 :evaluated? true :failed 0 :gate-ready? true :fp-cases [] :fn-cases []}
          fail  {:clean-fp 1 :recall 1.0 :evaluated? true :failed 0 :gate-ready? false
                 :fp-cases [{:fixture "c.clj" :judge-said "x"}] :fn-cases []}]
      (let [a (sut/aggregate [ready ready ready] 3 1)]
        (is (:gate-ready? a))
        (is (:stable? a)))
      (let [a (sut/aggregate [ready fail ready] 3 1)]
        (is (not (:gate-ready? a)) "one failing run -> not gate-ready")
        (is (not (:stable? a)) "verdict flipped across runs -> unstable")
        (is (= [true false true] (:run-verdicts a)))
        (is (= 1 (:clean-fp-max a)))))))

(deftest calibrate-end-to-end-test
  (testing "calibrate produces per-rule verdicts from an injected judge (no LLM)"
    (let [rules    [{:rule/id :r/reliable} {:rule/id :r/flaky}]
          fixtures [{:rel "clean.clj" :seeded #{}}
                    {:rel "viol.clj" :seeded #{:r/reliable :r/flaky}}]
          ;; reliable fires exactly on seeded; flaky also false-fires on the clean fixture
          judge-fn (fn [_rules f]
                     (let [fp (when (= "clean.clj" (:rel f)) #{:r/flaky})]
                       (sut/cell-success (into (:seeded f) fp) {})))
          record   (sut/calibrate {:rules rules :fixtures fixtures :judge-fn judge-fn
                                   :runs 2 :trials 1 :max-parallel 2 :gate-bar bar})]
      (is (get-in record [:r/reliable :gate-ready?]) "reliable: 0 fp, full recall")
      (is (not (get-in record [:r/flaky :gate-ready?])) "flaky false-fires on clean")
      (is (true? (:stable? (get record :r/reliable)))))))

;; ---- build-time gate-readiness check ----

(deftest gate-check-test
  (testing "a hard-halt semantic rule without a passing record is ungated; deterministic + non-acting rules are exempt"
    (let [rules  [{:rule/id :r/sem-ready :rule/detection {:type :custom} :rule/enforcement {:action :hard-halt}}
                  {:rule/id :r/sem-bad   :rule/detection {:type :custom} :rule/enforcement {:action :hard-halt}}
                  {:rule/id :r/scan      :rule/detection {:type :content-scan :pattern "x"} :rule/enforcement {:action :hard-halt}}
                  {:rule/id :r/warn-sem  :rule/detection {:type :custom} :rule/enforcement {:action :warn}}]
          record {:r/sem-ready {:gate-ready? true} :r/sem-bad {:gate-ready? false}}
          result (sut/gate-check rules record)]
      (is (not (:ok? result)))
      (is (= [:r/sem-bad] (:ungated result))
          "only the not-gate-ready hard-halt semantic rule is ungated (content-scan + warn exempt)"))))

(deftest shipped-pack-gate-readiness-test
  (testing "every hard-halt SEMANTIC rule in the shipped pack carries a passing calibration record"
    (let [{:keys [ok? ungated]} (sut/gate-check-shipped)]
      (is ok? (str "hard-halt semantic rules lacking a passing calibration record "
                   "(sharpen the rule + recalibrate, or reclassify): " (pr-str ungated))))))
