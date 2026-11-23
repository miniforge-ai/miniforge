;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.judge-test
  "Tests for the per-file judge calls, against a mock LLM."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.semantic-analyzer.judge :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} analyze-file-violations-test
  (testing "produces violations from mock LLM response"
    (let [rule       {:rule/id :std/stratified-design
                      :rule/title "Stratified Design"
                      :rule/category "001"
                      :rule/severity :high
                      :rule/knowledge-content "Use layers."}
          mock-complete (fn [_client _request]
                          {:success true
                           :content "[{:line 15 :current \"(require core)\" :message \"Cross-layer dependency\"}]"})
          result     (sut/analyze-file :mock mock-complete rule "src/foo.clj" "(ns foo (:require [core]))")]
      (is (= 1 (count result)))
      (is (= :std/stratified-design (:rule/id (first result))))
      (is (= "src/foo.clj" (:file (first result))))
      (is (= 15 (:line (first result))))
      (is (= "Cross-layer dependency" (:rationale (first result)))))))

(deftest ^{:stratum 0} analyze-file-clean-test
  (testing "returns empty violations when LLM says code is clean"
    (let [rule       {:rule/id :std/clean :rule/title "Clean" :rule/category "001"
                      :rule/severity :info :rule/knowledge-content "Be clean."}
          mock-complete (fn [_client _request]
                          {:success true :content "[]"})
          result     (sut/analyze-file nil mock-complete rule "src/ok.clj" "(defn ok [] :ok)")]
      (is (= [] result)))))

(def ^{:stratum 0} ^:private rule-t
  {:rule/id :std/test :rule/title "T" :rule/category "001"
   :rule/severity :info :rule/knowledge-content "K"})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} analyze-file-unparseable-answer-fails-closed-test
  (testing "an answer that does not parse is a judge failure on the file, not
            a clean result"
    (let [mock-complete (fn [_client _request]
                          {:success true :content "Sorry, I can't analyze this."})
          [v & more]    (sut/analyze-file nil mock-complete rule-t "f.clj" "code")]
      (is (nil? more))
      (is (= :std/test (:rule/id v)))
      (is (= "f.clj" (:file v)))
      (is (= :unparseable (:semantic/judge-failure v))))))

(deftest ^{:stratum 1} analyze-file-failed-call-fails-closed-test
  (testing "a failed judge call (:success false) is a judge failure carrying
            the error message"
    (let [mock-complete (fn [_client _request]
                          {:success false :error {:type "timeout" :message "judge timed out"}})
          [v & more]    (sut/analyze-file nil mock-complete rule-t "f.clj" "code")]
      (is (nil? more))
      (is (= :call-failed (:semantic/judge-failure v)))
      (is (re-find #"judge timed out" (:rationale v))))))

(deftest ^{:stratum 1} analyze-file-batched-failure-blocks-every-rule-test
  (testing "a failed batched call is a judge failure for every rule in the batch"
    (let [rule-b        (assoc rule-t :rule/id :std/b)
          mock-complete (fn [_client _request] {:success false :error {:message "down"}})
          result        (sut/analyze-file-batched nil mock-complete [rule-t rule-b] "f.clj" "code")]
      (is (= #{:std/test :std/b} (set (map :rule/id result))))
      (is (every? #(= :call-failed (:semantic/judge-failure %)) result)))))
