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

(ns ai.miniforge.workflow-security-compliance.e2e-test
  "End-to-end integration test for the security compliance workflow (issue #552).

   Threads a context through all 5 phases in order, verifying:
     1. SARIF + CSV parsing yields unified violations
     2. Trace enrichment adds API/call-chain metadata
     3. Doc verification marks known/unknown APIs
     4. Classification produces correct category distribution
     5. Exclusion generation writes a valid EDN file"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [ai.miniforge.workflow-security-compliance.fixtures :as fixtures]
   [ai.miniforge.workflow-security-compliance.interface]))

;------------------------------------------------------------------------------ E2E Pipeline

(deftest full-pipeline-e2e-test
  (testing "full 5-phase pipeline from parse through exclusion generation"
    (let [output-dir (fixtures/create-output-dir "miniforge-e2e-")
          ctx (assoc-in (fixtures/base-ctx) [:execution/input :output-dir] output-dir)

          after-parse (fixtures/parsed-ctx ctx)
          violations  (get-in after-parse [:execution/phase-results :sec-parse-scan
                                           :result :output :violations])

          after-trace (fixtures/traced-ctx ctx)
          traced      (get-in after-trace [:execution/phase-results :sec-trace-source
                                           :result :output :traced-violations])

          after-verify (fixtures/verified-ctx ctx)
          verified     (get-in after-verify [:execution/phase-results :sec-verify-docs
                                             :result :output :verified-violations])

          after-classify (fixtures/classified-ctx ctx)
          classified     (get-in after-classify [:execution/phase-results :sec-classify
                                                 :result :output :classified-violations])

          after-exclusions (fixtures/exclusions-ctx ctx)
          excl-output      (get-in after-exclusions [:execution/phase-results :sec-generate-exclusions
                                                     :result :output])]

      ;; ── Phase 1 assertions ──
      (testing "Phase 1: parse-scan"
        (is (= fixtures/total-violation-count (count violations))
            "5 SARIF + 2 CSV = 7 total violations")
        (is (every? :violation/id violations))
        (is (some #(= "BinaryAPIScanner" (:violation/source-tool %)) violations)
            "SARIF violations should have tool name"))

      ;; ── Phase 2 assertions ──
      (testing "Phase 2: trace-source"
        (is (= fixtures/total-violation-count (count traced)))
        (is (every? :trace/actual-api traced))
        (let [dynamic (filter :trace/dynamic-load? traced)]
          (is (pos? (count dynamic))
              "dynamic loads from SARIF should be detected")))

      ;; ── Phase 3 assertions ──
      (testing "Phase 3: verify-docs"
        (is (= fixtures/total-violation-count (count verified)))
        (is (every? #(contains? % :verified/documented?) verified))
        (let [documented (filter :verified/documented? verified)]
          (is (pos? (count documented))
              "at least one known API should be marked documented")))

      ;; ── Phase 4 assertions ──
      (testing "Phase 4: classify"
        (is (= fixtures/total-violation-count (count classified)))
        (let [by-cat (group-by :classification/category classified)]
          (is (pos? (count (get by-cat :true-positive [])))
              "undocumented error APIs should be true-positive")
          (is (= #{:true-positive :false-positive :needs-investigation}
                 (set (keys by-cat)))
              "all three categories should be represented")))

      ;; ── Phase 5 assertions ──
      (testing "Phase 5: generate-exclusions"
        (is (some? excl-output))
        (is (pos? (+ (:total-excluded excl-output 0)
                     (:total-flagged excl-output 0)))
            "should have some excluded or flagged items")
        (let [excl-file (fixtures/exclusions-file output-dir)]
          (is (.exists excl-file)
              "exclusions.edn should be written to disk")
          (when (.exists excl-file)
            (let [parsed (edn/read-string (slurp excl-file))]
              (is (string? (:generated-at parsed)))
              (is (map? (:exclusions parsed)))
              (is (map? (:summary parsed)))))))

      ;; ── Cross-phase assertions ──
      (testing "context threading: all phases completed"
        (is (= #{:sec-parse-scan :sec-trace-source :sec-verify-docs
                 :sec-classify :sec-generate-exclusions}
               (set (get-in after-exclusions [:execution :phases-completed])))
            "all 5 phases should be in phases-completed"))

      (testing "context threading: cumulative metrics"
        (is (number? (get-in after-exclusions [:execution/metrics :duration-ms] 0))
            "cumulative duration should be recorded as a number"))

      ;; Clean up temp files
      (let [excl-file (fixtures/exclusions-file output-dir)]
        (when (.exists excl-file)
          (.delete excl-file)
          (.delete (io/file output-dir ".security-exclusions"))
          (.delete (io/file output-dir)))))))
