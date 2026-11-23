;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
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
(ns ai.miniforge.data-quality.rule-errors-test
  "A rule that fails to run is an error, never a pass and never a domain
   violation."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.data-quality.interface :as dq]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(def ^{:stratum 0} ^:private records [{:id "1"} {:id "2"}])

(defn- ^{:stratum 0} rule-returning
  "A custom rule whose check fn returns `verdict` for every record."
  [verdict]
  (dq/custom-rule :fixture-rule (constantly verdict)))

(defn- ^{:stratum 0} throwing-rule []
  (dq/custom-rule :fixture-rule (fn [_] (throw (ex-info "rule blew up" {})))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} error-kinds [rules]
  (mapv :error/kind (dq/rule-errors (dq/evaluate-records rules records))))

;; Tests
(deftest ^{:stratum 1} test-throwing-rule-is-a-rule-error
  (testing "given a check fn that throws → one :rule/threw error per record, with the exception type"
    (let [errors (dq/rule-errors (dq/evaluate-records [(throwing-rule)] records))]
      (is (= [:rule/threw :rule/threw] (mapv :error/kind errors)))
      (is (= [0 1] (mapv :record-index errors)))
      (is (every? #(= "clojure.lang.ExceptionInfo" (:error/type %)) errors))
      (is (every? #(= :fixture-rule (:rule/id %)) errors)))))

(deftest ^{:stratum 1} test-malformed-verdict-never-passes-a-record
  (testing "given a truthy but malformed :valid? → the record does not pass the filter"
    (let [evaluation (dq/evaluate-records [(rule-returning {:valid? "yes"})] records)]
      (is (empty? (dq/filter-passed evaluation))))))

(deftest ^{:stratum 1} test-domain-violation-is-not-a-rule-error
  (testing "given a rule that ran and rejected a record → a violation, not a rule error"
    (let [rule       (dq/custom-rule :non-empty-id #(hash-map :valid? (= "1" (:id %))))
          evaluation (dq/evaluate-records [rule] records)]
      (is (empty? (dq/rule-errors evaluation)))
      (is (= [{:id "1"}] (dq/filter-passed evaluation))))))

(deftest ^{:stratum 1} test-assertion-failure-is-a-rule-error
  (testing "given a check fn whose assert fails → :rule/threw with java.lang.AssertionError, not an escaped error"
    (let [rule   (dq/custom-rule :asserting-rule (fn [_] (assert false "rule assertion")))
          errors (dq/rule-errors (dq/evaluate-records [rule] records))]
      (is (= [:rule/threw :rule/threw] (mapv :error/kind errors)))
      (is (every? #(= "java.lang.AssertionError" (:error/type %)) errors)))))

(deftest ^{:stratum 1} test-warning-rule-error-blocks-the-record
  (testing "given a warning-severity rule that throws → the record does not pass the filter"
    (let [rule (dq/custom-rule :warning-rule (fn [_] (throw (ex-info "boom" {}))) :severity :warning)]
      (is (empty? (dq/filter-passed (dq/evaluate-records [rule] records)))))))

(deftest ^{:stratum 1} test-report-keeps-rule-errors-apart-from-violations
  (testing "given one record a rule could not check → the report lists the rule error, no violation, and counts the record failed"
    (let [rule   (dq/custom-rule :id-1-only #(if (= "1" (:id %))
                                               {:valid? true}
                                               (throw (ex-info "cannot check" {}))))
          report (dq/generate-report :fixture-pack (dq/evaluate-records [rule] records))]
      (is (= [] (:report/violations report)))
      (is (= [1] (mapv :record-index (:report/rule-errors report))))
      (is (= 1 (:report/passed report)))
      (is (= 1 (:report/failed report))))))

(deftest ^{:stratum 1} test-field-summary-counts-rule-errors-against-the-field
  (testing "given a field whose rule failed to run on one of two records → its pass rate is 0.5, not 1.0"
    (let [rule   (assoc (dq/custom-rule :id-1-only #(if (= "1" (:id %))
                                                       {:valid? true}
                                                       (throw (ex-info "cannot check" {}))))
                        :rule/field :id)
          report (dq/generate-report :fixture-pack (dq/evaluate-records [rule] records))]
      (is (= {"id" {:violations 0 :rule-errors 1 :pass-rate 0.5}}
             (:report/field-summary report))))))

(deftest ^{:stratum 1} test-verdict-cannot-inject-a-rule-error
  (testing "given a well-formed verdict with its own :rule/error → the rule ran; no rule error is recorded"
    (let [evaluation (dq/evaluate-records [(rule-returning {:valid? true :rule/error true})] records)]
      (is (empty? (dq/rule-errors evaluation)))
      (is (= records (dq/filter-passed evaluation))))))

(deftest ^{:stratum 1} test-malformed-verdict-reports-runtime-class-only
  (testing "given a malformed verdict whose :type metadata holds record data → the error names the runtime class, not the metadata"
    (let [verdict (with-meta {} {:type {:secret "record contents"}})
          errors  (dq/rule-errors (dq/evaluate-records [(rule-returning verdict)] records))]
      (is (every? #(= "clojure.lang.PersistentArrayMap" (:error/verdict-type %)) errors))
      (is (not-any? #(re-find #"record contents" (pr-str %)) errors))
      (is (= ["nil" "nil"]
             (mapv :error/verdict-type (dq/rule-errors (dq/evaluate-records [(rule-returning nil)] records))))))))

(deftest ^{:stratum 1} test-field-pass-rate-counts-each-failed-record-once
  (testing "given two rules on one field that both fail the same record (a violation and a rule error) → that record counts once"
    (let [rejects-2 (assoc (dq/custom-rule :rejects-2 #(hash-map :valid? (not= "2" (:id %))))
                           :rule/field :id)
          throws-2  (assoc (dq/custom-rule :throws-2 #(if (= "2" (:id %))
                                                        (throw (ex-info "cannot check" {}))
                                                        {:valid? true}))
                           :rule/field :id)
          report    (dq/generate-report :fixture-pack (dq/evaluate-records [rejects-2 throws-2] records))]
      (is (= {"id" {:violations 1 :rule-errors 1 :pass-rate 0.5}}
             (:report/field-summary report))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-malformed-verdicts-are-rule-errors
  (testing "given verdicts that are not a map with a boolean :valid? → :rule/malformed-verdict"
    (doseq [verdict [nil true {:valid? "yes"} {:message "no valid? key"}]]
      (is (= [:rule/malformed-verdict :rule/malformed-verdict]
             (error-kinds [(rule-returning verdict)]))
          (pr-str verdict)))))

(deftest ^{:stratum 2} test-verdict-cannot-select-the-exception-path
  (testing "given a verdict shaped like the boundary's own thrown tag → a malformed verdict, not :rule/threw"
    (is (= [:rule/malformed-verdict :rule/malformed-verdict]
           (error-kinds [(rule-returning [:ai.miniforge.data-quality.invoke/threw
                                          (ex-info "not thrown" {})])])))))
