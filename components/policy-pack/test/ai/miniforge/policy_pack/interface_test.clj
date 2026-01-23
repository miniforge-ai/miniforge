(ns ai.miniforge.policy-pack.interface-test
  "Tests for the policy-pack component public interface."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.policy-pack.interface :as pp]))

;------------------------------------------------------------------------------ Layer 0
;; Schema validation tests

(deftest valid-rule-test
  (testing "Valid rule passes validation"
    (let [rule {:rule/id :test-rule
                :rule/title "Test Rule"
                :rule/description "A test rule"
                :rule/severity :major
                :rule/category "800"
                :rule/applies-to {}
                :rule/detection {:type :content-scan
                                 :pattern "TODO"}
                :rule/enforcement {:action :warn
                                   :message "Found violation"}}]
      (is (pp/valid-rule? rule))))

  (testing "Invalid rule fails validation"
    (is (not (pp/valid-rule? {:rule/id "not-a-keyword"})))
    (is (not (pp/valid-rule? {:rule/id :test :rule/severity :invalid})))))

(deftest validate-rule-test
  (testing "Returns valid result for valid rule"
    (let [rule {:rule/id :test
                :rule/title "Test"
                :rule/description "Desc"
                :rule/severity :minor
                :rule/category "800"
                :rule/applies-to {}
                :rule/detection {:type :content-scan}
                :rule/enforcement {:action :warn :message "Warning"}}
          result (pp/validate-rule rule)]
      (is (:valid? result))
      (is (nil? (:errors result)))))

  (testing "Returns errors for invalid rule"
    (let [result (pp/validate-rule {:rule/id "not-keyword"})]
      (is (not (:valid? result)))
      (is (some? (:errors result))))))

(deftest valid-pack-test
  (testing "Valid pack passes validation"
    (let [now (java.time.Instant/now)
          pack {:pack/id "test-pack"
                :pack/name "Test Pack"
                :pack/version "2026.01.22"
                :pack/description "A test pack"
                :pack/author "test-author"
                :pack/categories []
                :pack/rules []
                :pack/created-at now
                :pack/updated-at now}]
      (is (pp/valid-pack? pack))))

  (testing "Invalid pack fails validation"
    (is (not (pp/valid-pack? {:pack/id 123})))))

;------------------------------------------------------------------------------ Layer 1
;; Registry tests

(deftest registry-crud-test
  (testing "Register and retrieve pack"
    (let [reg (pp/create-registry)
          now (java.time.Instant/now)
          pack {:pack/id "test"
                :pack/name "Test Pack"
                :pack/version "2026.01.22"
                :pack/description "Test"
                :pack/author "author"
                :pack/categories []
                :pack/rules []
                :pack/created-at now
                :pack/updated-at now}]
      ;; Register
      (pp/register-pack reg pack)

      ;; Retrieve
      (let [retrieved (pp/get-pack reg "test")]
        (is (= "test" (:pack/id retrieved)))
        (is (= "Test Pack" (:pack/name retrieved))))

      ;; Retrieve specific version
      (let [retrieved (pp/get-pack-version reg "test" "2026.01.22")]
        (is (some? retrieved)))))

  (testing "Delete pack"
    (let [reg (pp/create-registry)
          now (java.time.Instant/now)
          pack {:pack/id "to-delete"
                :pack/name "Delete Me"
                :pack/version "2026.01.01"
                :pack/description "Test"
                :pack/author "author"
                :pack/categories []
                :pack/rules []
                :pack/created-at now
                :pack/updated-at now}]
      (pp/register-pack reg pack)
      (is (some? (pp/get-pack reg "to-delete")))
      (is (true? (pp/delete-pack reg "to-delete" "2026.01.01")))
      (is (nil? (pp/get-pack reg "to-delete"))))))

(deftest registry-list-test
  (testing "List packs with criteria"
    (let [reg (pp/create-registry)
          now (java.time.Instant/now)
          pack1 {:pack/id "pack-a"
                 :pack/name "Pack Alpha"
                 :pack/version "2026.01.01"
                 :pack/description "First pack"
                 :pack/author "alice"
                 :pack/categories [{:category/id "300" :category/name "Infra" :category/rules []}]
                 :pack/rules []
                 :pack/created-at now
                 :pack/updated-at now}
          pack2 {:pack/id "pack-b"
                 :pack/name "Pack Beta"
                 :pack/version "2026.01.02"
                 :pack/description "Second pack"
                 :pack/author "bob"
                 :pack/categories []
                 :pack/rules []
                 :pack/created-at now
                 :pack/updated-at now}]
      (pp/register-pack reg pack1)
      (pp/register-pack reg pack2)

      ;; List all
      (let [all (pp/list-packs reg {})]
        (is (= 2 (count all))))

      ;; Filter by author
      (let [alice-packs (pp/list-packs reg {:author "alice"})]
        (is (= 1 (count alice-packs)))
        (is (= "pack-a" (:pack/id (first alice-packs)))))

      ;; Search by name
      (let [beta-packs (pp/list-packs reg {:search "beta"})]
        (is (= 1 (count beta-packs)))
        (is (= "pack-b" (:pack/id (first beta-packs))))))))

;------------------------------------------------------------------------------ Layer 2
;; Pack and rule builders test

(deftest create-pack-test
  (testing "Creates pack with required fields"
    (let [pack (pp/create-pack "my-pack" "My Pack" "Description" "me")]
      (is (= "my-pack" (:pack/id pack)))
      (is (= "My Pack" (:pack/name pack)))
      (is (= "me" (:pack/author pack)))
      (is (string? (:pack/version pack)))
      (is (inst? (:pack/created-at pack)))))

  (testing "Creates pack with options"
    (let [rule {:rule/id :test
                :rule/title "Test"
                :rule/description "Desc"
                :rule/severity :minor
                :rule/category "800"
                :rule/applies-to {}
                :rule/detection {:type :content-scan}
                :rule/enforcement {:action :warn :message "Warning"}}
          pack (pp/create-pack "my-pack" "My Pack" "Desc" "me"
                               :version "2026.01.15"
                               :license "Apache-2.0"
                               :rules [rule])]
      (is (= "2026.01.15" (:pack/version pack)))
      (is (= "Apache-2.0" (:pack/license pack)))
      (is (= 1 (count (:pack/rules pack)))))))

(deftest create-rule-test
  (testing "Creates rule with builder"
    (let [rule (pp/create-rule
                :no-todos
                "No TODOs"
                "Forbid TODO comments"
                :minor
                "800"
                (pp/content-scan-detection "TODO")
                (pp/warn-enforcement "Found TODO"))]
      (is (= :no-todos (:rule/id rule)))
      (is (= :minor (:rule/severity rule)))
      (is (= :content-scan (get-in rule [:rule/detection :type])))
      (is (= :warn (get-in rule [:rule/enforcement :action])))))

  (testing "Creates rule with options"
    (let [rule (pp/create-rule
                :test
                "Test"
                "Test rule"
                :major
                "300"
                (pp/diff-analysis-detection "^-")
                (pp/halt-enforcement "Violation!" {:remediation "Fix it"})
                :applies-to {:task-types #{:import}}
                :agent-behavior "Don't do that")]
      (is (= #{:import} (get-in rule [:rule/applies-to :task-types])))
      (is (= "Don't do that" (:rule/agent-behavior rule)))
      (is (= "Fix it" (get-in rule [:rule/enforcement :remediation]))))))

(deftest detection-builders-test
  (testing "Content scan detection"
    (let [det (pp/content-scan-detection "pattern")]
      (is (= :content-scan (:type det)))
      (is (= "pattern" (:pattern det))))

    (let [det (pp/content-scan-detection "pattern" {:context-lines 5})]
      (is (= 5 (:context-lines det)))))

  (testing "Diff analysis detection"
    (let [det (pp/diff-analysis-detection "^-")]
      (is (= :diff-analysis (:type det)))
      (is (= "^-" (:pattern det)))))

  (testing "Plan output detection"
    (let [det (pp/plan-output-detection ["pattern1" "pattern2"])]
      (is (= :plan-output (:type det)))
      (is (= ["pattern1" "pattern2"] (:patterns det))))))

(deftest enforcement-builders-test
  (testing "Warn enforcement"
    (let [enf (pp/warn-enforcement "Warning message")]
      (is (= :warn (:action enf)))
      (is (= "Warning message" (:message enf)))))

  (testing "Halt enforcement"
    (let [enf (pp/halt-enforcement "Halt!")]
      (is (= :hard-halt (:action enf))))

    (let [enf (pp/halt-enforcement "Halt!" {:remediation "Fix it"})]
      (is (= "Fix it" (:remediation enf)))))

  (testing "Approval enforcement"
    (let [enf (pp/approval-enforcement "Needs approval" [:human :security])]
      (is (= :require-approval (:action enf)))
      (is (= [:human :security] (:approvers enf))))))

;------------------------------------------------------------------------------ Layer 3
;; Detection and checking tests

(deftest check-artifact-test
  (testing "Detects content violations"
    (let [pack (pp/create-pack "test" "Test" "Test pack" "author"
                               :rules
                               [(pp/create-rule :no-todos "No TODOs" "Desc"
                                                :minor "800"
                                                (pp/content-scan-detection "TODO")
                                                (pp/warn-enforcement "Found TODO"))])
          result (pp/check-artifact pack
                                    {:artifact/content "# TODO: fix this"
                                     :artifact/path "main.py"}
                                    {})]
      (is (:passed? result))  ; Warnings don't block
      (is (= 1 (count (:violations result))))
      (is (= 1 (count (:warnings result))))))

  (testing "Blocking violations fail"
    (let [pack (pp/create-pack "test" "Test" "Test pack" "author"
                               :rules
                               [(pp/create-rule :no-secrets "No Secrets" "Desc"
                                                :critical "500"
                                                (pp/content-scan-detection "SECRET_KEY")
                                                (pp/halt-enforcement "Found secret!"))])
          result (pp/check-artifact pack
                                    {:artifact/content "SECRET_KEY=abc123"
                                     :artifact/path "config.py"}
                                    {})]
      (is (not (:passed? result)))
      (is (= 1 (count (:blocking result))))))

  (testing "No violations pass"
    (let [pack (pp/create-pack "test" "Test" "Test pack" "author"
                               :rules
                               [(pp/create-rule :no-todos "No TODOs" "Desc"
                                                :minor "800"
                                                (pp/content-scan-detection "TODO")
                                                (pp/warn-enforcement "Found TODO"))])
          result (pp/check-artifact pack
                                    {:artifact/content "# Clean code"
                                     :artifact/path "main.py"}
                                    {})]
      (is (:passed? result))
      (is (empty? (:violations result))))))

(deftest check-multiple-packs-test
  (testing "Rules from multiple packs are checked"
    (let [pack1 (pp/create-pack "pack1" "Pack 1" "First" "author"
                                :rules
                                [(pp/create-rule :no-todos "No TODOs" "Desc"
                                                 :minor "800"
                                                 (pp/content-scan-detection "TODO")
                                                 (pp/warn-enforcement "Found TODO"))])
          pack2 (pp/create-pack "pack2" "Pack 2" "Second" "author"
                                :rules
                                [(pp/create-rule :no-fixmes "No FIXMEs" "Desc"
                                                 :minor "800"
                                                 (pp/content-scan-detection "FIXME")
                                                 (pp/warn-enforcement "Found FIXME"))])
          result (pp/check-artifact [pack1 pack2]
                                    {:artifact/content "# TODO: and FIXME:"}
                                    {})]
      (is (= 2 (count (:violations result)))))))

;------------------------------------------------------------------------------ Layer 4
;; Rule resolution tests

(deftest resolve-rules-test
  (testing "Later rules override earlier ones"
    (let [rule1 {:rule/id :test
                 :rule/severity :minor
                 :rule/enforcement {:action :warn :message "Warn"}}
          rule2 {:rule/id :test
                 :rule/severity :major
                 :rule/enforcement {:action :hard-halt :message "Halt"}}
          resolved (pp/resolve-rules [rule1 rule2])]
      (is (= 1 (count resolved)))
      ;; Severity escalates to higher
      (is (= :major (:rule/severity (first resolved))))
      ;; Enforcement escalates to stricter
      (is (= :hard-halt (get-in (first resolved) [:rule/enforcement :action])))))

  (testing "Different rule IDs are preserved"
    (let [rule1 {:rule/id :rule-a :rule/severity :minor}
          rule2 {:rule/id :rule-b :rule/severity :major}
          resolved (pp/resolve-rules [rule1 rule2])]
      (is (= 2 (count resolved))))))

;------------------------------------------------------------------------------ Layer 5
;; Utility function tests

(deftest glob-matches-test
  (testing "Simple glob matching"
    (is (pp/glob-matches? "*.tf" "main.tf"))
    (is (not (pp/glob-matches? "*.tf" "main.py")))
    (is (not (pp/glob-matches? "*.tf" "dir/main.tf"))))

  (testing "Double star glob matching"
    (is (pp/glob-matches? "**/*.tf" "main.tf"))
    (is (pp/glob-matches? "**/*.tf" "modules/vpc/main.tf"))
    (is (not (pp/glob-matches? "**/*.tf" "main.py")))))

(deftest compare-versions-test
  (testing "Version comparison"
    (is (pos? (pp/compare-versions "2026.01.22" "2026.01.15")))
    (is (neg? (pp/compare-versions "2025.12.01" "2026.01.01")))
    (is (zero? (pp/compare-versions "2026.01.22" "2026.01.22")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run all tests
  (clojure.test/run-tests 'ai.miniforge.policy-pack.interface-test)

  :leave-this-here)
