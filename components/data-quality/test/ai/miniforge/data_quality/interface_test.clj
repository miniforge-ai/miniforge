(ns ai.miniforge.data-quality.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.data-quality.interface :as dq]))

;; ---------------------------------------------------------------------------
;; Rule constructors
;; ---------------------------------------------------------------------------

(deftest required-rule-test
  (testing "Required rule — present value passes"
    (let [rule (dq/required-rule :cik)
          result ((:rule/check-fn rule) {:cik "0001234567"})]
      (is (:valid? result))))

  (testing "Required rule — nil value fails"
    (let [rule (dq/required-rule :cik)
          result ((:rule/check-fn rule) {:cik nil})]
      (is (not (:valid? result)))))

  (testing "Required rule — missing key fails"
    (let [rule (dq/required-rule :cik)
          result ((:rule/check-fn rule) {:name "ACME"})]
      (is (not (:valid? result)))))

  (testing "Required rule — empty string fails"
    (let [rule (dq/required-rule :cik)
          result ((:rule/check-fn rule) {:cik ""})]
      (is (not (:valid? result))))))

(deftest type-check-rule-test
  (testing "Type check — string passes"
    (let [rule (dq/type-check-rule :name :string)
          result ((:rule/check-fn rule) {:name "ACME Corp"})]
      (is (:valid? result))))

  (testing "Type check — wrong type fails"
    (let [rule (dq/type-check-rule :name :string)
          result ((:rule/check-fn rule) {:name 42})]
      (is (not (:valid? result)))))

  (testing "Type check — nil passes (nil means absent, not wrong type)"
    (let [rule (dq/type-check-rule :name :string)
          result ((:rule/check-fn rule) {:name nil})]
      (is (:valid? result))))

  (testing "Type check — long/int"
    (let [rule (dq/type-check-rule :count :long)
          result ((:rule/check-fn rule) {:count 42})]
      (is (:valid? result)))))

(deftest range-rule-test
  (testing "Range rule — value in range passes"
    (let [rule (dq/range-rule :market_cap 0 1e15)
          result ((:rule/check-fn rule) {:market_cap 5000000.0})]
      (is (:valid? result))))

  (testing "Range rule — value below min fails"
    (let [rule (dq/range-rule :market_cap 0 1e15)
          result ((:rule/check-fn rule) {:market_cap -100})]
      (is (not (:valid? result)))))

  (testing "Range rule — value above max fails"
    (let [rule (dq/range-rule :market_cap 0 1e15)
          result ((:rule/check-fn rule) {:market_cap 2e15})]
      (is (not (:valid? result)))))

  (testing "Range rule — nil passes"
    (let [rule (dq/range-rule :market_cap 0 1e15)
          result ((:rule/check-fn rule) {:market_cap nil})]
      (is (:valid? result)))))

(deftest pattern-rule-test
  (testing "Pattern rule — matching value passes"
    (let [rule (dq/pattern-rule :cik #"\d{10}")
          result ((:rule/check-fn rule) {:cik "0001234567"})]
      (is (:valid? result))))

  (testing "Pattern rule — non-matching value fails"
    (let [rule (dq/pattern-rule :cik #"\d{10}")
          result ((:rule/check-fn rule) {:cik "abc"})]
      (is (not (:valid? result)))))

  (testing "Pattern rule — nil passes"
    (let [rule (dq/pattern-rule :cik #"\d{10}")
          result ((:rule/check-fn rule) {:cik nil})]
      (is (:valid? result))))

  (testing "Pattern rule — default severity is :warning"
    (let [rule (dq/pattern-rule :cik #"\d+")]
      (is (= :warning (:rule/severity rule))))))

(deftest custom-rule-test
  (testing "Custom rule — passes"
    (let [rule (dq/custom-rule :positive-value
                               (fn [r] (if (pos? (:value r))
                                         {:valid? true}
                                         {:valid? false :message "Must be positive"})))
          result ((:rule/check-fn rule) {:value 10})]
      (is (:valid? result))))

  (testing "Custom rule — fails"
    (let [rule (dq/custom-rule :positive-value
                               (fn [r] (if (pos? (:value r))
                                         {:valid? true}
                                         {:valid? false :message "Must be positive"})))
          result ((:rule/check-fn rule) {:value -1})]
      (is (not (:valid? result)))
      (is (= "Must be positive" (:message result))))))

;; ---------------------------------------------------------------------------
;; Evaluate records
;; ---------------------------------------------------------------------------

(deftest evaluate-records-single-rule-test
  (testing "Evaluate records with single rule"
    (let [rules [(dq/required-rule :cik)]
          records [{:cik "001"} {:cik nil} {:name "X"}]
          result (dq/evaluate-records rules records)]
      (is (= 3 (count result)))
      ;; First record passes
      (is (:valid? (first (:evaluations (first result)))))
      ;; Second record fails
      (is (not (:valid? (first (:evaluations (second result)))))))))

(deftest evaluate-records-multiple-rules-test
  (testing "Evaluate records with multiple rules"
    (let [rules [(dq/required-rule :cik)
                 (dq/type-check-rule :market_cap :long)]
          records [{:cik "001" :market_cap 5000}
                   {:cik nil :market_cap "not-a-number"}]
          result (dq/evaluate-records rules records)]
      ;; First record: both pass
      (is (every? :valid? (:evaluations (first result))))
      ;; Second record: both fail
      (is (every? (complement :valid?) (:evaluations (second result)))))))

;; ---------------------------------------------------------------------------
;; Filter passed
;; ---------------------------------------------------------------------------

(deftest filter-passed-test
  (testing "Filter keeps records passing all error-severity rules"
    (let [rules [(dq/required-rule :cik)]
          records [{:cik "001" :name "A"} {:cik nil :name "B"} {:cik "003" :name "C"}]
          evaluation (dq/evaluate-records rules records)
          passed (dq/filter-passed evaluation)]
      (is (= 2 (count passed)))
      (is (= "A" (:name (first passed))))
      (is (= "C" (:name (second passed)))))))

(deftest filter-passed-warnings-pass-through-test
  (testing "Warnings do not cause filtering"
    (let [rules [(dq/required-rule :cik :severity :warning)]
          records [{:cik nil :name "A"}]
          evaluation (dq/evaluate-records rules records)
          passed (dq/filter-passed evaluation)]
      (is (= 1 (count passed)))
      (is (= "A" (:name (first passed)))))))

;; ---------------------------------------------------------------------------
;; Generate report
;; ---------------------------------------------------------------------------

(deftest generate-report-test
  (testing "Report generation with field summaries"
    (let [rules [(dq/required-rule :cik)
                 (dq/range-rule :market_cap 0 1e15)]
          records [{:cik "001" :market_cap 5000}
                   {:cik nil :market_cap 5000}
                   {:cik "003" :market_cap -100}
                   {:cik "004" :market_cap 1000}]
          evaluation (dq/evaluate-records rules records)
          report (dq/generate-report :financial-quality evaluation)]
      (is (= :financial-quality (:report/pack-id report)))
      (is (= 4 (:report/total report)))
      (is (= 2 (:report/passed report)))
      (is (= 2 (:report/failed report)))
      (is (= 2 (count (:report/violations report))))
      ;; Field summary
      (is (contains? (:report/field-summary report) "cik"))
      (is (= 1 (get-in report [:report/field-summary "cik" :violations]))))))

(deftest generate-report-all-pass-test
  (testing "Report with all records passing"
    (let [rules [(dq/required-rule :id)]
          records [{:id 1} {:id 2}]
          evaluation (dq/evaluate-records rules records)
          report (dq/generate-report :all-pass evaluation)]
      (is (= 2 (:report/passed report)))
      (is (= 0 (:report/failed report)))
      (is (empty? (:report/violations report))))))

;; ---------------------------------------------------------------------------
;; Financial filing scenario (integration-style)
;; ---------------------------------------------------------------------------

(deftest financial-filing-quality-test
  (testing "Financial filing quality rules"
    (let [rules [(dq/required-rule :cik)
                 (dq/type-check-rule :filing_date :string)
                 (dq/range-rule :market_cap 0 1e15)]
          records [{:cik "0001234567" :filing_date "2024-01-15" :market_cap 50000000}
                   {:cik nil :filing_date "2024-02-01" :market_cap 1000000}
                   {:cik "0009876543" :filing_date 20240301 :market_cap -500}
                   {:cik "0001111111" :filing_date "2024-03-15" :market_cap 75000000}]
          evaluation (dq/evaluate-records rules records)
          passed (dq/filter-passed evaluation)
          report (dq/generate-report :sec-filings evaluation)]
      ;; Records 0 and 3 pass, record 1 fails (nil cik), record 2 fails (bad type + negative cap)
      (is (= 2 (count passed)))
      (is (= "0001234567" (:cik (first passed))))
      (is (= "0001111111" (:cik (second passed))))
      (is (= 2 (:report/failed report)))
      (is (pos? (count (:report/violations report)))))))
