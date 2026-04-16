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

(ns ai.miniforge.workflow-security-compliance.phases-test
  "Unit tests for the 5-phase security compliance workflow (issue #552).

   Tests phase interceptors individually:
     :sec-parse-scan           — SARIF/CSV parsing
     :sec-trace-source         — source tracing (stub)
     :sec-verify-docs          — documentation verification (mixed/stub)
     :sec-classify             — deterministic classification
     :sec-generate-exclusions  — exclusion file generation"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.workflow-security-compliance.phases :as phases]
   [ai.miniforge.workflow-security-compliance.interface]))

;------------------------------------------------------------------------------ Helpers

(def ^:private test-fixtures-dir
  "test/fixtures")

(defn- fixture-path [filename]
  (str test-fixtures-dir "/" filename))

(defn base-ctx
  "Minimal execution context for testing."
  []
  {:execution/id            (random-uuid)
   :execution/input         {:scan-paths  [(fixture-path "sample-scan.sarif")
                                           (fixture-path "sample-scan.csv")]
                             :output-dir  (str (System/getProperty "java.io.tmpdir")
                                               "/miniforge-test-" (System/currentTimeMillis))}
   :execution/metrics       {:duration-ms 0}
   :execution/phase-results {}})

;------------------------------------------------------------------------------ Registry Tests

(deftest phases-registered-in-registry-test
  (testing "all five security compliance phases are registered after namespace load"
    (is (some? (registry/phase-defaults :sec-parse-scan))
        ":sec-parse-scan defaults should be registered")
    (is (some? (registry/phase-defaults :sec-trace-source))
        ":sec-trace-source defaults should be registered")
    (is (some? (registry/phase-defaults :sec-verify-docs))
        ":sec-verify-docs defaults should be registered")
    (is (some? (registry/phase-defaults :sec-classify))
        ":sec-classify defaults should be registered")
    (is (some? (registry/phase-defaults :sec-generate-exclusions))
        ":sec-generate-exclusions defaults should be registered")))

(deftest phase-interceptors-are-retrievable-test
  (testing "get-phase-interceptor returns valid interceptor maps for each phase"
    (doseq [phase-kw [:sec-parse-scan :sec-trace-source :sec-verify-docs
                       :sec-classify :sec-generate-exclusions]]
      (let [interceptor (registry/get-phase-interceptor {:phase phase-kw})]
        (is (map? interceptor) (str phase-kw " should return a map"))
        (is (fn? (:enter interceptor)) (str phase-kw " should have an :enter fn"))
        (is (fn? (:leave interceptor)) (str phase-kw " should have a :leave fn"))
        (is (fn? (:error interceptor)) (str phase-kw " should have an :error fn"))))))

;------------------------------------------------------------------------------ Phase 1: Parse Scan

(deftest enter-sec-parse-scan-with-sarif-test
  (testing "parse-scan phase extracts violations from SARIF fixture"
    (let [ctx      (assoc-in (base-ctx) [:execution/input :scan-paths]
                             [(fixture-path "sample-scan.sarif")])
          result   (phases/enter-sec-parse-scan ctx)
          violations (get-in result [:phase :result :output :violations])]
      (is (= 5 (count violations))
          "SARIF fixture has 5 results")
      (is (every? :violation/id violations)
          "every violation should have an id")
      (is (every? :violation/rule-id violations)
          "every violation should have a rule-id")
      (is (every? :violation/severity violations)
          "every violation should have a severity"))))

(deftest enter-sec-parse-scan-with-csv-test
  (testing "parse-scan phase extracts violations from CSV fixture"
    (let [ctx      (assoc-in (base-ctx) [:execution/input :scan-paths]
                             [(fixture-path "sample-scan.csv")])
          result   (phases/enter-sec-parse-scan ctx)
          violations (get-in result [:phase :result :output :violations])]
      (is (= 2 (count violations))
          "CSV fixture has 2 data rows")
      (is (every? :violation/id violations))
      (is (every? #(= "csv-import" (:violation/source-tool %)) violations)))))

(deftest enter-sec-parse-scan-mixed-formats-test
  (testing "parse-scan handles both SARIF and CSV in a single pass"
    (let [ctx    (base-ctx)
          result (phases/enter-sec-parse-scan ctx)
          violations (get-in result [:phase :result :output :violations])]
      (is (= 7 (count violations))
          "5 SARIF + 2 CSV = 7 total violations"))))

(deftest enter-sec-parse-scan-no-files-test
  (testing "parse-scan returns empty when no scan paths given"
    (let [ctx    (assoc-in (base-ctx) [:execution/input :scan-paths] [])
          result (phases/enter-sec-parse-scan ctx)
          violations (get-in result [:phase :result :output :violations])]
      (is (empty? violations)))))

(deftest leave-sec-parse-scan-stores-metrics-test
  (testing "leave-sec-parse-scan populates metrics and phase-results"
    (let [ctx    (-> (base-ctx)
                     phases/enter-sec-parse-scan
                     (assoc-in [:phase :started-at] (- (System/currentTimeMillis) 100)))
          result (phases/leave-sec-parse-scan ctx)]
      (is (= :completed (get-in result [:phase :status])))
      (is (pos? (get-in result [:phase :metrics :violation-count])))
      (is (some? (get-in result [:execution/phase-results :sec-parse-scan :result]))))))

;------------------------------------------------------------------------------ Phase 2: Trace Source

(deftest enter-sec-trace-source-enriches-violations-test
  (testing "trace-source stub enriches each violation with trace fields"
    (let [ctx    (-> (base-ctx)
                     phases/enter-sec-parse-scan
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-parse-scan)
          result (phases/enter-sec-trace-source ctx)
          traced (get-in result [:phase :result :output :traced-violations])]
      (is (= 7 (count traced)))
      (is (every? :trace/actual-api traced))
      (is (every? :trace/call-chain traced))
      (is (every? #(contains? % :trace/dynamic-load?) traced)))))

(deftest trace-source-detects-dynamic-loads-test
  (testing "dynamic load patterns are flagged in trace"
    (let [ctx    (-> (base-ctx)
                     (assoc-in [:execution/input :scan-paths]
                               [(fixture-path "sample-scan.sarif")])
                     phases/enter-sec-parse-scan
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-parse-scan)
          result (phases/enter-sec-trace-source ctx)
          traced (get-in result [:phase :result :output :traced-violations])
          dynamic (filter :trace/dynamic-load? traced)]
      (is (pos? (count dynamic))
          "at least one SARIF violation involves dynamic loading"))))

;------------------------------------------------------------------------------ Phase 3: Verify Docs

(deftest enter-sec-verify-docs-marks-known-apis-test
  (testing "known APIs are marked as documented"
    (let [ctx    (-> (base-ctx)
                     phases/enter-sec-parse-scan
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-parse-scan
                     phases/enter-sec-trace-source
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-trace-source)
          result (phases/enter-sec-verify-docs ctx)
          verified (get-in result [:phase :result :output :verified-violations])]
      (is (= 7 (count verified)))
      (is (every? #(contains? % :verified/documented?) verified))
      (is (every? #(contains? % :verified/public?) verified))
      ;; CryptEncrypt is in known-apis, so at least one should be documented
      (is (pos? (count (filter :verified/documented? verified)))
          "at least one API from known-apis set should be documented"))))

;------------------------------------------------------------------------------ Phase 4: Classify

(deftest enter-sec-classify-categorizes-violations-test
  (testing "classify assigns categories to all violations"
    (let [ctx    (-> (base-ctx)
                     phases/enter-sec-parse-scan
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-parse-scan
                     phases/enter-sec-trace-source
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-trace-source
                     phases/enter-sec-verify-docs
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-verify-docs)
          result (phases/enter-sec-classify ctx)
          classified (get-in result [:phase :result :output :classified-violations])]
      (is (= 7 (count classified)))
      (is (every? :classification/category classified))
      (is (every? :classification/confidence classified))
      (is (every? #(#{:true-positive :false-positive :needs-investigation}
                     (:classification/category %))
                  classified)
          "all categories must be in the expected set"))))

(deftest classify-assigns-correct-categories-test
  (testing "classification rules produce correct categories"
    ;; Documented + non-dynamic → false-positive
    ;; Undocumented + error → true-positive
    ;; Otherwise → needs-investigation
    (let [ctx    (-> (base-ctx)
                     (assoc-in [:execution/input :scan-paths]
                               [(fixture-path "sample-scan.sarif")])
                     phases/enter-sec-parse-scan
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-parse-scan
                     phases/enter-sec-trace-source
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-trace-source
                     phases/enter-sec-verify-docs
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-verify-docs)
          result (phases/enter-sec-classify ctx)
          classified (get-in result [:phase :result :output :classified-violations])
          by-category (group-by :classification/category classified)]
      ;; The SARIF has API-001 (error, undocumented ntdll/user32) → true-positive
      (is (pos? (count (get by-category :true-positive [])))
          "should have at least one true-positive")
      ;; CryptEncrypt is in known-apis, error but documented → false-positive
      ;; (documented + non-dynamic = false-positive)
      (is (some #(or (= :false-positive (:classification/category %))
                     (= :needs-investigation (:classification/category %)))
                classified)
          "should have documented items classified as FP or needs-investigation"))))

;------------------------------------------------------------------------------ Phase 5: Generate Exclusions

(deftest enter-sec-generate-exclusions-writes-file-test
  (testing "generate-exclusions writes EDN exclusion file and produces output"
    (let [output-dir (str (System/getProperty "java.io.tmpdir")
                          "/miniforge-excl-test-" (System/currentTimeMillis))
          ctx    (-> (base-ctx)
                     (assoc-in [:execution/input :output-dir] output-dir)
                     phases/enter-sec-parse-scan
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-parse-scan
                     phases/enter-sec-trace-source
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-trace-source
                     phases/enter-sec-verify-docs
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-verify-docs
                     phases/enter-sec-classify
                     (assoc-in [:phase :started-at] (System/currentTimeMillis))
                     phases/leave-sec-classify)
          result (phases/enter-sec-generate-exclusions ctx)
          output (get-in result [:phase :result :output])
          excl-file (io/file output-dir ".security-exclusions" "exclusions.edn")]
      (is (some? output))
      (is (number? (:total-excluded output)))
      (is (number? (:total-flagged output)))
      (is (.exists excl-file)
          "exclusions.edn should be written to disk")
      ;; Clean up
      (.delete excl-file)
      (.delete (io/file output-dir ".security-exclusions"))
      (.delete (io/file output-dir)))))

;------------------------------------------------------------------------------ Error Handling

(deftest error-handlers-capture-exception-test
  (testing "error handlers set :failed status and store error info"
    (let [ctx (base-ctx)
          ex  (Exception. "test failure")]
      (doseq [error-fn [phases/error-sec-parse-scan
                         phases/error-sec-trace-source
                         phases/error-sec-verify-docs
                         phases/error-sec-classify
                         phases/error-sec-generate-exclusions]]
        (let [result (error-fn ctx ex)]
          (is (= :failed (get-in result [:phase :status])))
          (is (some? (get-in result [:phase :error]))))))))
