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

(ns ai.miniforge.policy-pack.detection-test
  "Tests for the policy-pack detection logic."
  (:require
   [clojure.string]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.policy-pack.capability :as capability]
   [ai.miniforge.policy-pack.detection :as detection]))

;------------------------------------------------------------------------------ Layer 0
;; Content scan detection tests

(deftest content-scan-test
  (testing "Detects pattern in content"
    (let [rule {:rule/id :no-todos
                :rule/detection {:type :content-scan
                                 :pattern "TODO"
                                 :context-lines 1}
                :rule/enforcement {:action :warn
                                   :message "Found TODO"}}
          artifact {:artifact/content "# TODO: implement this\ndef foo(): pass"
                    :artifact/path "main.py"}
          result (detection/detect-content-scan rule artifact {})]
      (is (some? result))
      (is (= :content-scan (:type result)))
      (is (= :no-todos (:rule-id result)))
      (is (= 1 (count (:matches result))))))

  (testing "No match returns nil"
    (let [rule {:rule/id :no-todos
                :rule/detection {:type :content-scan
                                 :pattern "TODO"}}
          artifact {:artifact/content "# Clean code here"
                    :artifact/path "main.py"}]
      (is (nil? (detection/detect-content-scan rule artifact {})))))

  (testing "Multiple patterns - any match triggers"
    (let [rule {:rule/id :no-debug
                :rule/detection {:type :content-scan
                                 :patterns ["console.log" "debugger"]}
                :rule/enforcement {:action :warn :message "Debug code found"}}
          artifact {:artifact/content "debugger;\nlet x = 1;"}]
      (is (some? (detection/detect-content-scan rule artifact {})))))

  (testing "Match includes context lines"
    (let [rule {:rule/id :test
                :rule/detection {:type :content-scan
                                 :pattern "ERROR"
                                 :context-lines 2}
                :rule/enforcement {:action :warn :message "Error"}}
          artifact {:artifact/content "line1\nline2\nERROR here\nline4\nline5"}
          result (detection/detect-content-scan rule artifact {})
          match (first (:matches result))]
      (is (= 3 (:line match)))
      (is (clojure.string/includes? (:context match) "line2"))
      (is (clojure.string/includes? (:context match) "line4")))))

;; :mode :negative — absence of the pattern is the violation (a required
;; pattern, e.g. a copyright header or a k8s resource limit). Regression: the
;; detector previously ignored :mode and always flagged presence, so every
;; negative-mode rule (require-*, header-copyright) ran inverted.

(deftest content-scan-negative-mode-test
  (let [require-header {:rule/id :require-header
                        :rule/detection {:type :content-scan
                                         :pattern "Christopher Lester"
                                         :mode :negative}
                        :rule/enforcement {:action :hard-halt
                                           :message "Missing copyright header"}}]
    (testing "negative mode: pattern PRESENT → no violation (requirement met)"
      (is (nil? (detection/detect-content-scan
                 require-header
                 {:artifact/content "Copyright Christopher Lester" :artifact/path "a.clj"}
                 {}))))

    (testing "negative mode: pattern ABSENT → violation (requirement unmet)"
      (let [result (detection/detect-content-scan
                    require-header
                    {:artifact/content "no header here" :artifact/path "a.clj"}
                    {})]
        (is (some? result))
        (is (= :require-header (:rule-id result)))))

    (testing "positive mode (default) is unaffected: presence is the violation"
      (let [pos {:rule/id :no-todo
                 :rule/detection {:type :content-scan :pattern "TODO"}
                 :rule/enforcement {:action :warn :message "TODO"}}]
        (is (some? (detection/detect-content-scan pos {:artifact/content "x TODO"} {})))
        (is (nil? (detection/detect-content-scan pos {:artifact/content "clean"} {})))))))

;------------------------------------------------------------------------------ Layer 1
;; Diff analysis detection tests

(deftest diff-analysis-test
  (testing "Detects pattern in diff"
    (let [rule {:rule/id :import-removal
                :rule/detection {:type :diff-analysis
                                 :pattern "^-\\s*import\\s*\\{"
                                 :context-lines 3}
                :rule/enforcement {:action :hard-halt
                                   :message "Cannot remove import blocks"}}
          artifact {:artifact/diff "- import {\n-   to = aws_s3_bucket.example\n- }"
                    :artifact/path "main.tf"}
          result (detection/detect-diff-analysis rule artifact {})]
      (is (some? result))
      (is (= :diff-analysis (:type result)))
      (is (= :import-removal (:rule-id result)))))

  (testing "Uses context diff if artifact diff absent"
    (let [rule {:rule/id :test
                :rule/detection {:type :diff-analysis
                                 :pattern "^-"}}
          result (detection/detect-diff-analysis
                    rule
                    {:artifact/path "test.tf"}
                    {:diff "- removed line\n+ added line"})]
      (is (some? result))))

  (testing "No match in diff"
    (let [rule {:rule/id :test
                :rule/detection {:type :diff-analysis
                                 :pattern "SECRET"}}
          artifact {:artifact/diff "+ added line\n+ another line"}]
      (is (nil? (detection/detect-diff-analysis rule artifact {}))))))

;------------------------------------------------------------------------------ Layer 2
;; Plan output detection tests

(deftest plan-output-test
  (testing "Detects terraform plan patterns"
    (let [rule {:rule/id :network-recreation
                :rule/detection {:type :plan-output
                                 :patterns ["-/\\+.*aws_route"]}
                :rule/applies-to {:resource-patterns ["aws_route"]}
                :rule/enforcement {:action :require-approval
                                   :message "Network resource recreation"}}
          context {:terraform-plan "# aws_route.main will be replaced\n  -/+ aws_route.main (tainted)"}
          result (detection/detect-plan-output rule {} context)]
      (is (some? result))
      (is (= :plan-output (:type result)))
      (is (seq (:resource-violations result)))))

  (testing "Parses resource changes from plan"
    (let [rule {:rule/id :destruction
                :rule/detection {:type :plan-output}
                :rule/applies-to {:resource-patterns ["aws_vpc" "aws_subnet"]}
                :rule/enforcement {:action :require-approval :message "Destruction"}}
          context {:terraform-plan "# aws_vpc.main will be destroyed\n# aws_subnet.private[0] must be replaced"}
          result (detection/detect-plan-output rule {} context)]
      (is (some? result))
      (is (= 2 (count (:resource-violations result)))))))

  (testing "No violations when no matching resources"
    (let [rule {:rule/id :test
                :rule/detection {:type :plan-output}
                :rule/applies-to {:resource-patterns ["aws_vpc"]}}
          context {:terraform-plan "# aws_s3_bucket.example will be created"}]
      (is (nil? (detection/detect-plan-output rule {} context)))))

;------------------------------------------------------------------------------ Layer 3
;; Unified detection tests

(deftest detect-violation-test
  (testing "Dispatches to correct detection type"
    (let [content-rule {:rule/id :content
                        :rule/detection {:type :content-scan :pattern "TODO"}
                        :rule/enforcement {:action :warn :message "TODO"}}
          diff-rule {:rule/id :diff
                     :rule/detection {:type :diff-analysis :pattern "^-"}
                     :rule/enforcement {:action :warn :message "Removal"}}]
      ;; Content scan
      (is (some? (detection/detect-violation
                  content-rule
                  {:artifact/content "# TODO"}
                  {})))

      ;; Diff analysis
      (is (some? (detection/detect-violation
                  diff-rule
                  {:artifact/diff "- removed"}
                  {})))))

  (testing "Returns nil for unsupported types"
    (let [rule {:rule/id :test
                :rule/detection {:type :state-comparison}}]
      (is (nil? (detection/detect-violation rule {} {}))))))

;------------------------------------------------------------------------------ Layer 4
;; Check rules tests

(deftest check-rules-test
  (testing "Checks multiple rules and returns violations"
    (let [rules [{:rule/id :no-todos
                  :rule/detection {:type :content-scan :pattern "TODO"}
                  :rule/enforcement {:action :warn :message "TODO found"}}
                 {:rule/id :no-fixmes
                  :rule/detection {:type :content-scan :pattern "FIXME"}
                  :rule/enforcement {:action :warn :message "FIXME found"}}]
          artifact {:artifact/content "# TODO: fix\n# FIXME: broken"}
          violations (detection/check-rules rules artifact {})]
      (is (= 2 (count violations)))
      (is (every? :rule violations))
      (is (every? :violation violations))
      (is (every? :timestamp violations))))

  (testing "Returns empty when no violations"
    (let [rules [{:rule/id :test
                  :rule/detection {:type :content-scan :pattern "SECRET"}}]
          artifact {:artifact/content "# Clean code"}]
      (is (empty? (detection/check-rules rules artifact {}))))))

;------------------------------------------------------------------------------ Layer 5
;; Violation classification tests

(deftest violation-classification-test
  (testing "Filters blocking violations"
    (let [violations [{:rule {:rule/enforcement {:action :hard-halt}}}
                      {:rule {:rule/enforcement {:action :warn}}}]]
      (is (= 1 (count (detection/blocking-violations violations))))))

  (testing "Filters approval-required violations"
    (let [violations [{:rule {:rule/enforcement {:action :require-approval}}}
                      {:rule {:rule/enforcement {:action :warn}}}]]
      (is (= 1 (count (detection/approval-required-violations violations))))))

  (testing "Filters warning violations"
    (let [violations [{:rule {:rule/enforcement {:action :warn}}}
                      {:rule {:rule/enforcement {:action :hard-halt}}}]]
      (is (= 1 (count (detection/warning-violations violations))))))

  (testing "Filters audit violations"
    (let [violations [{:rule {:rule/enforcement {:action :audit}}}
                      {:rule {:rule/enforcement {:action :warn}}}]]
      (is (= 1 (count (detection/audit-violations violations)))))))

(deftest violation-conversion-test
  (testing "Converts violation to error"
    (let [violation {:rule {:rule/id :test-rule
                            :rule/severity :high
                            :rule/enforcement {:action :hard-halt
                                               :message "Error!"
                                               :remediation "Fix it"}}
                     :violation {:matches [{:match "TODO" :line 1}]
                                 :artifact-path "main.py"
                                 :message "Error!"}}
          error (detection/violation->error violation)]
      (is (= :test-rule (:code error)))
      (is (= "Error!" (:message error)))
      (is (= :high (:severity error)))
      (is (= "Fix it" (:remediation error)))
      (is (= "main.py" (get-in error [:location :path]))
          "content-scan path still surfaces from :artifact-path")))

  (testing "Semantic violation surfaces the judge's nested file/line/snippet
            (not {:matches nil :path nil}) so the finding is actionable"
    (let [violation {:rule {:rule/id :std/named-constants
                            :rule/severity :high
                            :rule/enforcement {:action :hard-halt
                                               :message "Magic number."}}
                     :violation {:type :semantic
                                 :rule-id :std/named-constants
                                 :message "Magic number."
                                 :violations [{:rule/id :std/named-constants
                                               :file "components/x/src/x.clj"
                                               :line 42
                                               :current "(Thread/sleep 5000)"
                                               :rationale "5000 is a magic number"}]}}
          error (detection/violation->error violation)]
      (is (= "components/x/src/x.clj" (get-in error [:location :path]))
          "path comes from the judge's :file")
      (is (= 42 (get-in error [:location :matches 0 :line]))
          "line is preserved from the nested violation")
      (is (= "(Thread/sleep 5000)" (get-in error [:location :matches 0 :current]))
          "the offending snippet reaches the agent")))

  (testing "Converts violation to warning"
    (let [violation {:rule {:rule/id :test-rule
                            :rule/severity :low}
                     :violation {:message "Warning"}}
          warning (detection/violation->warning violation)]
      (is (= :test-rule (:code warning)))
      (is (= :low (:severity warning))))))

;------------------------------------------------------------------------------ Layer 1
;; Capability detection tests
;;
;; A stub :lint capability is registered via register-capability! so these
;; tests do not depend on clj-kondo or the gate layer.

(defn- stub-lint-check
  "Flags a violation when the artifact content contains the token BADLINT."
  [artifact _context]
  (when (clojure.string/includes? (str (:artifact/content artifact)) "BADLINT")
    {:type :capability :capability :lint :message "lint error"}))

(deftest detect-capability-registered-violation-test
  (testing "a :capability :lint rule surfaces the registered check's violation"
    (capability/register-capability!
     ::stub-lint {:meta {:tool :stub} :check stub-lint-check})
    (let [rule     {:rule/id :no-lint-errors
                    :rule/severity :high
                    :rule/detection {:type :capability :capability ::stub-lint}}
          artifact {:artifact/content "(defn x [] BADLINT)"
                    :artifact/path "core.clj"}
          result   (detection/detect-capability rule artifact {})]
      (is (some? result))
      (is (= :no-lint-errors (:rule-id result)))
      (is (= :high (:severity result)) "severity is preserved onto the violation")
      (is (= :lint (:capability result)) "check's own violation fields pass through"))))

(deftest detect-capability-clean-test
  (testing "a clean artifact yields nil (no violation) through the registered check"
    (capability/register-capability!
     ::stub-lint-clean {:meta {:tool :stub} :check stub-lint-check})
    (let [rule     {:rule/id :no-lint-errors
                    :rule/severity :high
                    :rule/detection {:type :capability :capability ::stub-lint-clean}}
          artifact {:artifact/content "(defn x [] 42)" :artifact/path "core.clj"}]
      (is (nil? (detection/detect-capability rule artifact {}))))))

(deftest detect-capability-unregistered-fails-loud-test
  (testing "an unregistered capability returns a :capability-error, not a silent pass"
    (let [rule   {:rule/id :missing-cap
                  :rule/severity :critical
                  :rule/detection {:type :capability :capability ::never-registered}}
          result (detection/detect-capability rule {:artifact/content "x"} {})]
      (is (= :capability-error (:type result)))
      (is (= :missing-cap (:rule-id result)))
      (is (= :critical (:severity result)))
      (is (= ::never-registered (:capability result))))))

(deftest detect-violation-dispatches-capability-test
  (testing "detect-violation routes :capability detection to detect-capability"
    (capability/register-capability!
     ::stub-dispatch {:meta {:tool :stub} :check stub-lint-check})
    (let [rule     {:rule/id :dispatched
                    :rule/severity :low
                    :rule/detection {:type :capability :capability ::stub-dispatch}}
          artifact {:artifact/content "BADLINT" :artifact/path "core.clj"}
          result   (detection/detect-violation rule artifact {})]
      (is (= :dispatched (:rule-id result)))
      (is (= :low (:severity result))))))

;------------------------------------------------------------------------------ Layer 1
;; Semantic (LLM-as-judge) detection tests

(def ^:private resolvable-custom-fn-sym
  'ai.miniforge.policy-pack.detection-test/a-resolvable-custom-fn)

(defn a-resolvable-custom-fn
  "A real custom detection fn used to prove resolvable :custom-fn rules stay
   on the deterministic detect-custom path (not routed to the judge)."
  [_artifact _context]
  {:matches ["custom-hit"]})

(use-fixtures
  :once
  (fn [run-tests]
    (detection/register-custom-fn! resolvable-custom-fn-sym a-resolvable-custom-fn)
    (try
      (run-tests)
      (finally
        (detection/unregister-custom-fn! resolvable-custom-fn-sym)))))

(deftest register-custom-fn-validates-detector-predicate-test
  (testing "custom detector registration rejects non-functions"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"two-arity predicate function"
         (detection/register-custom-fn! 'test/not-a-detector {:not :a-fn}))))
  (testing "custom detector registration rejects functions without artifact/context arity"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"two-arity predicate function"
         (detection/register-custom-fn! 'test/wrong-arity (fn [_artifact] nil)))))
  (testing "custom detector registration accepts a two-arity detector"
    (let [detector (fn [_artifact _context] nil)]
      (is (identical? detector
                      (detection/register-custom-fn! 'test/two-arity detector)))
      (detection/unregister-custom-fn! 'test/two-arity))))

(deftest custom-fn-resolvable-test
  (testing "a :custom rule with a resolvable :custom-fn is resolvable"
    (is (detection/custom-fn-resolvable?
         {:rule/detection
          {:type :custom
           :custom-fn resolvable-custom-fn-sym}})))
  (testing "a :custom rule with no :custom-fn is not resolvable"
    (is (not (detection/custom-fn-resolvable? {:rule/detection {:type :custom}}))))
  (testing "a :custom rule with an unresolvable :custom-fn is not resolvable"
    (is (not (detection/custom-fn-resolvable?
              {:rule/detection {:type :custom :custom-fn 'no.such.ns/missing}})))))

(deftest detect-semantic-routes-no-custom-fn-rule-test
  (testing "a :custom rule with no :custom-fn routes to analyze-rule and adapts the shape"
    (let [captured (atom nil)
          rule     {:rule/id :dry-violation
                    :rule/severity :error
                    :rule/detection {:type :custom}
                    :rule/enforcement {:message "Do not repeat yourself"}}
          ;; Stub the injected analyze-rule-shaped fn (mirrors semantic-analyzer).
          analyze  (fn [llm-client complete-fn repo-path r]
                     (reset! captured {:llm-client llm-client
                                       :complete-fn complete-fn
                                       :repo-path repo-path
                                       :rule r})
                     {:rule/id (:rule/id r)
                      :violations [{:file "a.clj" :reason "dup"}]
                      :status :completed})
          context  {:semantic-analyze-fn analyze
                    :llm-client :stub-client
                    :complete-fn (fn [_])
                    :repo-path "/tmp/repo"}
          result   (detection/detect-violation rule {} context)]
      (testing "analyze-rule was invoked with the context-derived wiring"
        (is (= :stub-client (:llm-client @captured)))
        (is (= "/tmp/repo" (:repo-path @captured)))
        (is (= rule (:rule @captured))))
      (testing "judge result is adapted to the policy-pack violation shape"
        (is (= :semantic (:type result)))
        (is (= :dry-violation (:rule-id result)))
        (is (= :error (:severity result)))
        (is (= [{:file "a.clj" :reason "dup"}] (:violations result)))
        (is (= "Do not repeat yourself" (:message result)))))))

(deftest detect-semantic-no-violation-returns-nil-test
  (testing "judge reporting no violations yields nil"
    (let [rule    {:rule/id :clean :rule/severity :error :rule/detection {:type :custom}}
          analyze (fn [_ _ _ _] {:violations [] :status :completed})
          context {:semantic-analyze-fn analyze
                   :llm-client :c :complete-fn (fn [_])}]
      (is (nil? (detection/detect-violation rule {} context))))))

(deftest detect-semantic-absent-wiring-returns-nil-test
  (testing "no semantic wiring in context -> nil, no throw"
    (let [rule {:rule/id :unwired :rule/severity :error :rule/detection {:type :custom}}]
      (is (nil? (detection/detect-violation rule {} {})))
      (testing "analyze-fn present but llm-client missing -> still nil"
        (is (nil? (detection/detect-violation
                   rule {} {:semantic-analyze-fn (fn [_ _ _ _] {:violations [{:x 1}]})})))))))

(deftest detect-custom-still-used-for-resolvable-fn-test
  (testing "a :custom rule WITH a resolvable :custom-fn stays on detect-custom"
    (let [rule   {:rule/id :resolvable
                  :rule/detection
                  {:type :custom
                   :custom-fn resolvable-custom-fn-sym}}
          ;; If this routed to the judge it would NPE on a nil analyze-fn /
          ;; return nil; detect-custom must produce the custom-tagged violation.
          result (detection/detect-violation rule {} {})]
      (is (= :custom (:type result)))
      (is (= :resolvable (:rule-id result)))
      (is (= ["custom-hit"] (:matches result))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run tests
  (clojure.test/run-tests 'ai.miniforge.policy-pack.detection-test)

  :leave-this-here)
