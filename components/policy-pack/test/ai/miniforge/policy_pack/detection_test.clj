(ns ai.miniforge.policy-pack.detection-test
  "Tests for the policy-pack detection logic."
  (:require
   [clojure.test :refer [deftest is testing]]
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
          artifact {:artifact/content "line1\nline2\nERROR here\nline4\nline5"}]
      (let [result (detection/detect-content-scan rule artifact {})
            match (first (:matches result))]
        (is (= 3 (:line match)))
        (is (clojure.string/includes? (:context match) "line2"))
        (is (clojure.string/includes? (:context match) "line4"))))))

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
                    :artifact/path "main.tf"}]
      (let [result (detection/detect-diff-analysis rule artifact {})]
        (is (some? result))
        (is (= :diff-analysis (:type result)))
        (is (= :import-removal (:rule-id result))))))

  (testing "Uses context diff if artifact diff absent"
    (let [rule {:rule/id :test
                :rule/detection {:type :diff-analysis
                                 :pattern "^-"}}]
      (let [result (detection/detect-diff-analysis
                    rule
                    {:artifact/path "test.tf"}
                    {:diff "- removed line\n+ added line"})]
        (is (some? result)))))

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
          context {:terraform-plan "# aws_route.main will be replaced\n  -/+ aws_route.main (tainted)"}]
      (let [result (detection/detect-plan-output rule {} context)]
        (is (some? result))
        (is (= :plan-output (:type result)))
        (is (seq (:resource-violations result))))))

  (testing "Parses resource changes from plan"
    (let [rule {:rule/id :destruction
                :rule/detection {:type :plan-output}
                :rule/applies-to {:resource-patterns ["aws_vpc" "aws_subnet"]}
                :rule/enforcement {:action :require-approval :message "Destruction"}}
          context {:terraform-plan "# aws_vpc.main will be destroyed\n# aws_subnet.private[0] must be replaced"}]
      (let [result (detection/detect-plan-output rule {} context)]
        (is (some? result))
        (is (= 2 (count (:resource-violations result)))))))

  (testing "No violations when no matching resources"
    (let [rule {:rule/id :test
                :rule/detection {:type :plan-output}
                :rule/applies-to {:resource-patterns ["aws_vpc"]}}
          context {:terraform-plan "# aws_s3_bucket.example will be created"}]
      (is (nil? (detection/detect-plan-output rule {} context))))))

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
                            :rule/severity :major
                            :rule/enforcement {:action :hard-halt
                                               :message "Error!"
                                               :remediation "Fix it"}}
                     :violation {:matches [{:match "TODO" :line 1}]
                                 :artifact-path "main.py"
                                 :message "Error!"}}
          error (detection/violation->error violation)]
      (is (= :test-rule (:code error)))
      (is (= "Error!" (:message error)))
      (is (= :major (:severity error)))
      (is (= "Fix it" (:remediation error)))))

  (testing "Converts violation to warning"
    (let [violation {:rule {:rule/id :test-rule
                            :rule/severity :minor}
                     :violation {:message "Warning"}}
          warning (detection/violation->warning violation)]
      (is (= :test-rule (:code warning)))
      (is (= :minor (:severity warning))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run tests
  (clojure.test/run-tests 'ai.miniforge.policy-pack.detection-test)

  :leave-this-here)
