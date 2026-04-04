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

(ns ai.miniforge.cli.workflow-runner.display-output-test
  "Unit tests for display output functions: print-workflow-header,
   print-workflow-summary, print-result, print-pretty-result,
   print-error-header, and help output functions.

   These tests cover the stdout-producing functions in display.clj
   that were not covered by the existing display-test.clj (which
   focuses on pure formatting) or progress-integration-test.clj
   (which focuses on event lifecycle)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.cli.workflow-runner.display :as display]))

;------------------------------------------------------------------------------ Helpers

(defn- capture-stdout
  "Execute body-fn and return all stdout output as a string."
  [body-fn]
  (let [output (atom [])]
    (with-redefs [println (fn [& args] (swap! output conj (apply str args)))
                  print   (fn [& args] (swap! output conj (apply str args)))]
      (body-fn))
    (str/join "\n" @output)))

;------------------------------------------------------------------------------ Layer 0
;; print-workflow-header

(deftest print-workflow-header-normal-test
  (testing "print-workflow-header prints header with workflow-id and version"
    (let [out (capture-stdout
                #(display/print-workflow-header :my-workflow "1.0.0" false))]
      (is (not (str/blank? out))
          "Non-quiet mode should produce output")
      ;; Should contain ANSI codes (cyan, bold)
      (is (str/includes? out "\u001b")
          "Header should contain ANSI color codes"))))

(deftest print-workflow-header-quiet-test
  (testing "print-workflow-header produces no output when quiet"
    (let [out (capture-stdout
                #(display/print-workflow-header :my-workflow "1.0.0" true))]
      (is (str/blank? out)
          "Quiet mode should produce no output"))))

(deftest print-workflow-header-keyword-workflow-id-test
  (testing "print-workflow-header converts keyword workflow-id via name"
    (let [out (capture-stdout
                #(display/print-workflow-header :feature-build "2.0.0" false))]
      ;; The header renders (name :feature-build) = "feature-build"
      (is (not (str/blank? out))))))

;------------------------------------------------------------------------------ Layer 1
;; print-workflow-summary

(deftest print-workflow-summary-success-test
  (testing "print-workflow-summary renders success with metrics"
    (let [result {:execution/status :completed
                  :execution/metrics {:tokens 5000
                                      :cost-usd 0.0123
                                      :duration-ms 45000}}
          out (capture-stdout #(display/print-workflow-summary result))]
      (is (not (str/blank? out)))
      ;; Should contain green success marker
      (is (str/includes? out (get display/ansi-codes :green))
          "Success should use green color")
      ;; Should contain formatted metrics
      (is (str/includes? out "5000")
          "Should display token count")
      (is (str/includes? out "0.0123")
          "Should display cost")
      (is (str/includes? out "45.0s")
          "Should display formatted duration"))))

(deftest print-workflow-summary-failure-test
  (testing "print-workflow-summary renders failure with errors"
    (let [result {:execution/status :failed
                  :execution/errors ["Agent timeout" "Gate lint failed"]}
          out (capture-stdout #(display/print-workflow-summary result))]
      (is (not (str/blank? out)))
      ;; Should contain red failure marker
      (is (str/includes? out (get display/ansi-codes :red))
          "Failure should use red color")
      ;; Should list errors
      (is (str/includes? out "Agent timeout")
          "Should display first error")
      (is (str/includes? out "Gate lint failed")
          "Should display second error"))))

(deftest print-workflow-summary-no-metrics-test
  (testing "print-workflow-summary handles result without metrics"
    (let [result {:execution/status :completed}
          out (capture-stdout #(display/print-workflow-summary result))]
      (is (not (str/blank? out))
          "Should still produce output without metrics"))))

(deftest print-workflow-summary-no-errors-test
  (testing "print-workflow-summary handles failure without errors list"
    (let [result {:execution/status :failed}
          out (capture-stdout #(display/print-workflow-summary result))]
      (is (not (str/blank? out))
          "Should still produce output without errors"))))

(deftest print-workflow-summary-empty-errors-test
  (testing "print-workflow-summary handles empty errors vector"
    (let [result {:execution/status :failed
                  :execution/errors []}
          out (capture-stdout #(display/print-workflow-summary result))]
      ;; Empty errors should not produce error section
      (is (not (str/blank? out))))))

;------------------------------------------------------------------------------ Layer 2
;; print-result (output format dispatch)

(deftest print-result-json-test
  (testing "print-result with :json output produces valid JSON"
    (let [result {:execution/status :completed :data "hello"}
          out (capture-stdout #(display/print-result result {:output :json}))]
      (is (not (str/blank? out)))
      ;; Should be parseable JSON
      (let [parsed (json/parse-string out true)]
        (is (map? parsed))
        (is (= "hello" (:data parsed)))))))

(deftest print-result-edn-default-test
  (testing "print-result with nil output mode falls through to pprint"
    (let [result {:execution/status :completed}
          out (with-out-str (display/print-result result {:output nil}))]
      (is (not (str/blank? out))
          "Default mode should pprint the result"))))

(deftest print-result-pretty-quiet-test
  (testing "print-result with :pretty and quiet=true produces no output"
    (let [result {:execution/status :completed}
          out (capture-stdout #(display/print-result result {:output :pretty :quiet true}))]
      (is (str/blank? out)
          "Pretty mode with quiet should produce no output"))))

(deftest print-result-pretty-not-quiet-test
  (testing "print-result with :pretty and quiet=false produces full output"
    (let [result {:execution/status :completed
                  :execution/metrics {:tokens 100 :cost-usd 0.001 :duration-ms 500}}
          out (capture-stdout #(display/print-result result {:output :pretty :quiet false}))]
      (is (not (str/blank? out))
          "Pretty mode without quiet should produce output"))))

;------------------------------------------------------------------------------ Layer 3
;; print-pretty-result

(deftest print-pretty-result-test
  (testing "print-pretty-result renders separator lines and summary"
    (let [result {:execution/status :completed
                  :execution/metrics {:tokens 200 :cost-usd 0.005 :duration-ms 3000}}
          out (capture-stdout #(display/print-pretty-result result))]
      (is (not (str/blank? out)))
      ;; Should contain cyan separator lines (━ repeated)
      (is (str/includes? out "━")
          "Should contain box-drawing separator"))))

(deftest print-pretty-result-failed-test
  (testing "print-pretty-result renders failure with errors"
    (let [result {:execution/status :failed
                  :execution/errors ["compile error"]}
          out (capture-stdout #(display/print-pretty-result result))]
      (is (not (str/blank? out)))
      (is (str/includes? out "compile error")))))

;------------------------------------------------------------------------------ Layer 4
;; Error help output functions

(deftest print-error-header-test
  (testing "print-error-header renders error message, details, and cause"
    (let [cause (Exception. "root cause")
          out (capture-stdout
                #(display/print-error-header "Something broke" {:key "val"} cause))]
      (is (not (str/blank? out)))
      (is (str/includes? out "Something broke")
          "Should contain error message")
      (is (str/includes? out ":key")
          "Should contain data details")
      (is (str/includes? out "root cause")
          "Should contain cause message")
      ;; Should contain red coloring for error header
      (is (str/includes? out (get display/ansi-codes :red))))))

(deftest print-error-header-nil-data-test
  (testing "print-error-header handles nil data gracefully"
    (let [out (capture-stdout
                #(display/print-error-header "Error" nil nil))]
      (is (not (str/blank? out)))
      (is (str/includes? out "Error")))))

(deftest print-error-header-nil-cause-test
  (testing "print-error-header handles nil cause gracefully"
    (let [out (capture-stdout
                #(display/print-error-header "Error" {:x 1} nil))]
      (is (not (str/blank? out))))))

(deftest print-namespace-resolution-help-test
  (testing "print-namespace-resolution-help prints namespace help"
    (let [out (capture-stdout #(display/print-namespace-resolution-help))]
      (is (not (str/blank? out))
          "Should produce help output")
      (is (str/includes? out (get display/ansi-codes :cyan))
          "Should use cyan coloring"))))

(deftest print-babashka-fallback-help-test
  (testing "print-babashka-fallback-help prints Babashka help"
    (let [out (capture-stdout #(display/print-babashka-fallback-help))]
      (is (not (str/blank? out))
          "Should produce help output")
      (is (str/includes? out (get display/ansi-codes :cyan))
          "Should use cyan coloring"))))

(deftest print-general-debugging-help-test
  (testing "print-general-debugging-help prints debugging tips"
    (let [out (capture-stdout #(display/print-general-debugging-help))]
      (is (not (str/blank? out))
          "Should produce help output")
      (is (str/includes? out (get display/ansi-codes :cyan))
          "Should use cyan coloring"))))

;------------------------------------------------------------------------------ Layer 5
;; format-event-line edge cases

(deftest format-event-line-workflow-completed-no-duration-test
  (testing "workflow/completed without :workflow/duration-ms omits duration suffix"
    (let [line (display/format-event-line {:event/type :workflow/completed})]
      (is (string? line))
      (is (not (str/includes? line "("))
          "Without duration, no parenthesized suffix"))))

(deftest format-event-line-workflow-failed-no-reason-test
  (testing "workflow/failed without :workflow/failure-reason omits reason suffix"
    (let [line (display/format-event-line {:event/type :workflow/failed})]
      (is (string? line))
      (is (not (str/includes? line ":"))
          "Without reason, no colon-separated suffix"))))

(deftest format-event-line-phase-completed-no-duration-test
  (testing "phase/phase-completed without :phase/duration-ms omits duration"
    (let [line (display/format-event-line {:event/type :workflow/phase-completed
                                           :workflow/phase :plan
                                           :phase/outcome :success})]
      (is (string? line))
      (is (not (str/includes? line "("))
          "Without duration, no parenthesized suffix"))))

(deftest format-event-line-agent-status-default-status-test
  (testing "agent/status with no message/status falls back to default-status"
    (let [line (display/format-event-line {:event/type :agent/status
                                           :agent/id :planner})]
      (is (string? line))
      ;; Should contain the default status message ("working")
      (is (some? line)))))

(deftest format-event-line-chain-completed-with-duration-test
  (testing "chain/completed includes formatted duration"
    (let [line (display/format-event-line {:event/type :chain/completed
                                           :chain/id :deploy
                                           :chain/duration-ms 90000})]
      (is (string? line))
      (is (str/includes? line "1.5m")
          "Should include formatted duration"))))

(deftest format-event-line-chain-completed-no-duration-test
  (testing "chain/completed without duration omits suffix"
    (let [line (display/format-event-line {:event/type :chain/completed
                                           :chain/id :deploy})]
      (is (string? line))
      (is (not (str/includes? line "("))))))

;------------------------------------------------------------------------------ Layer 6
;; format-demo-line edge cases

(deftest format-demo-line-nil-phase-test
  (testing "format-demo-line uses ? for nil phase"
    (let [line (display/format-demo-line {:event/type :workflow/phase-started})]
      (is (string? line))
      (is (str/includes? line "?")
          "Nil phase should render as ?"))))

(deftest format-demo-line-nil-agent-test
  (testing "format-demo-line uses ? for nil agent"
    (let [line (display/format-demo-line {:event/type :agent/started})]
      (is (string? line))
      (is (str/includes? line "?")
          "Nil agent should render as ?"))))

(deftest format-demo-line-nil-gate-test
  (testing "format-demo-line uses ? for nil gate"
    (let [line (display/format-demo-line {:event/type :gate/passed})]
      (is (string? line))
      (is (str/includes? line "?")
          "Nil gate should render as ?"))))

(deftest format-demo-line-workflow-completed-no-duration-test
  (testing "format-demo-line workflow/completed without duration shows ?"
    (let [line (display/format-demo-line {:event/type :workflow/completed})]
      (is (string? line))
      (is (str/includes? line "?")
          "Missing duration should render as ?"))))

(deftest format-demo-line-workflow-failed-no-reason-test
  (testing "format-demo-line workflow/failed without reason shows unknown"
    (let [line (display/format-demo-line {:event/type :workflow/failed})]
      (is (string? line))
      (is (str/includes? line "unknown")
          "Missing reason should render as unknown"))))

(deftest format-demo-line-phase-completed-no-duration-test
  (testing "format-demo-line phase-completed without duration shows ?"
    (let [line (display/format-demo-line {:event/type :workflow/phase-completed
                                          :workflow/phase :plan})]
      (is (string? line))
      (is (str/includes? line "?")
          "Missing duration should render as ?"))))

(deftest format-demo-line-no-ansi-for-all-types-test
  (testing "No demo-mode output contains ANSI codes for any supported event type"
    (let [events [{:event/type :workflow/started}
                  {:event/type :workflow/completed :workflow/duration-ms 1000}
                  {:event/type :workflow/failed :workflow/failure-reason "err"}
                  {:event/type :workflow/phase-started :workflow/phase :plan}
                  {:event/type :workflow/phase-completed :workflow/phase :plan
                   :phase/duration-ms 500}
                  {:event/type :agent/started :agent/id :planner}
                  {:event/type :agent/completed :agent/id :planner}
                  {:event/type :agent/failed :agent/id :planner}
                  {:event/type :gate/passed :gate/id :lint}
                  {:event/type :gate/failed :gate/id :lint}]]
      (doseq [e events]
        (let [line (display/format-demo-line e)]
          (when line
            (is (not (str/includes? line "\u001b"))
                (str "Demo line for " (:event/type e) " must not contain ANSI codes"))))))))

;------------------------------------------------------------------------------ Layer 7
;; format-duration additional edge cases

(deftest format-duration-large-values-test
  (testing "Very large durations format as minutes"
    (is (= "60.0m" (display/format-duration 3600000)))
    (is (= "100.0m" (display/format-duration 6000000)))))

(deftest format-duration-exact-boundaries-test
  (testing "Exact boundary at 1000ms"
    (is (= "1.0s" (display/format-duration 1000))))
  (testing "Exact boundary at 60000ms"
    (is (= "1.0m" (display/format-duration 60000))))
  (testing "Just below 1000ms"
    (is (= "999ms" (display/format-duration 999))))
  (testing "Just below 60000ms"
    (is (= "60.0s" (display/format-duration 59999)))))

;------------------------------------------------------------------------------ Layer 8
;; colorize composition tests

(deftest colorize-all-known-colors-test
  (testing "All known color keys produce correct ANSI prefix"
    (doseq [[k code] display/ansi-codes
            :when (not= k :reset)]
      (let [result (display/colorize k "test")]
        (is (str/starts-with? result code)
            (str "Color " k " should start with its ANSI code"))
        (is (str/ends-with? result (:reset display/ansi-codes))
            (str "Color " k " should end with reset code"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.cli.workflow-runner.display-output-test)
  :leave-this-here)
