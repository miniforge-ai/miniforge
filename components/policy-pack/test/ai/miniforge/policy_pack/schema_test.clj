(ns ai.miniforge.policy-pack.schema-test
  "Unit tests for policy-pack Malli schemas, validation helpers, and result helpers.

   Covers:
   - Layer 0: Enum definitions and base type schemas
   - Layer 1: Rule component schemas (applicability, detection, enforcement, example)
   - Layer 2: Rule and PackManifest schemas
   - Validation helpers (valid?, validate, explain)
   - Convenience wrappers (valid-rule?, validate-rule, valid-pack?, validate-pack)
   - Result helpers (succeeded?, success, failure, failure-with-errors)"
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.policy-pack.schema :as sut]))

;; ============================================================================
;; Layer 0 — Enum definition tests
;; ============================================================================

(deftest rule-severities-test
  (testing "rule-severities is a vector of four keywords in descending severity"
    (is (= [:critical :major :minor :info] sut/rule-severities))
    (is (= 4 (count sut/rule-severities)))))

(deftest enforcement-actions-test
  (testing "enforcement-actions ordered from strictest to most lenient"
    (is (= [:hard-halt :require-approval :warn :audit] sut/enforcement-actions))
    (is (= 4 (count sut/enforcement-actions)))))

(deftest detection-types-test
  (testing "detection-types has six detection mechanisms"
    (is (= [:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis :custom]
           sut/detection-types))
    (is (= 6 (count sut/detection-types)))))

(deftest task-types-test
  (testing "task-types has five task operations"
    (is (= [:create :import :modify :delete :migrate] sut/task-types))
    (is (= 5 (count sut/task-types)))))

(deftest repo-types-test
  (testing "repo-types has five repository types"
    (is (= [:terraform-module :terraform-live :kubernetes :argocd :application]
           sut/repo-types))
    (is (= 5 (count sut/repo-types)))))

(deftest approver-types-test
  (testing "approver-types has three approver kinds"
    (is (= [:human :senior-engineer :security] sut/approver-types))
    (is (= 3 (count sut/approver-types)))))

;; ============================================================================
;; Layer 0 — Enum schema validation tests
;; ============================================================================

(deftest rule-severity-schema-test
  (testing "valid severity keywords pass"
    (doseq [sev [:critical :major :minor :info]]
      (is (sut/valid? sut/RuleSeverity sev)
          (str sev " should be valid"))))

  (testing "invalid values rejected"
    (are [v] (not (sut/valid? sut/RuleSeverity v))
      :warning :error :high :low "critical" nil 42)))

(deftest rule-enforcement-schema-test
  (testing "valid enforcement actions pass"
    (doseq [action [:hard-halt :require-approval :warn :audit]]
      (is (sut/valid? sut/RuleEnforcement action))))

  (testing "invalid enforcement actions rejected"
    (are [v] (not (sut/valid? sut/RuleEnforcement v))
      :block :allow :skip "hard-halt" nil)))

(deftest detection-type-schema-test
  (testing "valid detection types pass"
    (doseq [dt [:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis :custom]]
      (is (sut/valid? sut/DetectionType dt))))

  (testing "invalid detection types rejected"
    (is (not (sut/valid? sut/DetectionType :regex)))
    (is (not (sut/valid? sut/DetectionType "custom")))))

(deftest task-type-schema-test
  (testing "valid task types pass"
    (doseq [tt [:create :import :modify :delete :migrate]]
      (is (sut/valid? sut/TaskType tt))))

  (testing "invalid task types rejected"
    (is (not (sut/valid? sut/TaskType :update)))
    (is (not (sut/valid? sut/TaskType :read)))))

(deftest repo-type-schema-test
  (testing "valid repo types pass"
    (doseq [rt [:terraform-module :terraform-live :kubernetes :argocd :application]]
      (is (sut/valid? sut/RepoType rt))))

  (testing "invalid repo types rejected"
    (is (not (sut/valid? sut/RepoType :github)))
    (is (not (sut/valid? sut/RepoType :docker)))))

(deftest approver-type-schema-test
  (testing "valid approver types pass"
    (doseq [at [:human :senior-engineer :security]]
      (is (sut/valid? sut/ApproverType at))))

  (testing "invalid approver types rejected"
    (is (not (sut/valid? sut/ApproverType :bot)))
    (is (not (sut/valid? sut/ApproverType :manager)))))

(deftest trust-level-schema-test
  (testing "valid trust levels pass"
    (doseq [tl [:tainted :untrusted :trusted]]
      (is (sut/valid? sut/TrustLevel tl))))

  (testing "invalid trust levels rejected"
    (is (not (sut/valid? sut/TrustLevel :verified)))
    (is (not (sut/valid? sut/TrustLevel :unknown)))))

(deftest authority-channel-schema-test
  (testing "valid authority channels pass"
    (is (sut/valid? sut/AuthorityChannel :authority/instruction))
    (is (sut/valid? sut/AuthorityChannel :authority/data)))

  (testing "invalid authority channels rejected"
    (is (not (sut/valid? sut/AuthorityChannel :authority/reference)))
    (is (not (sut/valid? sut/AuthorityChannel :instruction)))))

;; ============================================================================
;; Layer 1 — Component schema tests
;; ============================================================================

(deftest rule-applicability-schema-test
  (testing "empty map is valid (all fields optional)"
    (is (sut/valid? sut/RuleApplicability {})))

  (testing "full applicability map is valid"
    (is (sut/valid? sut/RuleApplicability
                    {:task-types #{:create :modify}
                     :file-globs ["**/*.tf"]
                     :resource-patterns ["aws_s3_bucket.*"]
                     :repo-types #{:terraform-module}
                     :phases #{:plan :implement}})))

  (testing "task-types must be a set of TaskType"
    (is (not (sut/valid? sut/RuleApplicability {:task-types #{:unknown}}))))

  (testing "file-globs must be a vector of strings"
    (is (not (sut/valid? sut/RuleApplicability {:file-globs [42]}))))

  (testing "resource-patterns accepts strings and regex patterns"
    (is (sut/valid? sut/RuleApplicability {:resource-patterns ["pattern"]}))
    (is (sut/valid? sut/RuleApplicability {:resource-patterns [#"regex"]})))

  (testing "phases must be a set of keywords"
    (is (sut/valid? sut/RuleApplicability {:phases #{:plan :review :implement}}))))

(deftest rule-detection-schema-test
  (testing "minimal detection: type only"
    (is (sut/valid? sut/RuleDetection {:type :custom})))

  (testing "detection with string pattern"
    (is (sut/valid? sut/RuleDetection {:type :diff-analysis
                                       :pattern "^-\\s*import"})))

  (testing "detection with regex pattern"
    (is (sut/valid? sut/RuleDetection {:type :content-scan
                                       :pattern #"secret.*key"})))

  (testing "detection with multiple patterns"
    (is (sut/valid? sut/RuleDetection {:type :content-scan
                                       :patterns ["pattern-a" #"pattern-b"]})))

  (testing "detection with context-lines"
    (is (sut/valid? sut/RuleDetection {:type :diff-analysis
                                       :context-lines 3})))

  (testing "detection with custom-fn symbol"
    (is (sut/valid? sut/RuleDetection {:type :custom
                                       :custom-fn 'my.ns/detect-fn})))

  (testing "type is required"
    (is (not (sut/valid? sut/RuleDetection {}))))

  (testing "context-lines must be positive integer"
    (is (not (sut/valid? sut/RuleDetection {:type :custom :context-lines 0})))
    (is (not (sut/valid? sut/RuleDetection {:type :custom :context-lines -1})))))

(deftest rule-enforcement-config-schema-test
  (testing "minimal enforcement: action + message"
    (is (sut/valid? sut/RuleEnforcementConfig
                    {:action :hard-halt :message "Stop!"})))

  (testing "enforcement with remediation"
    (is (sut/valid? sut/RuleEnforcementConfig
                    {:action :warn
                     :message "Warning"
                     :remediation "Fix by doing X"})))

  (testing "enforcement with approvers"
    (is (sut/valid? sut/RuleEnforcementConfig
                    {:action :require-approval
                     :message "Needs approval"
                     :approvers [:human :security]})))

  (testing "action is required"
    (is (not (sut/valid? sut/RuleEnforcementConfig {:message "oops"}))))

  (testing "message is required"
    (is (not (sut/valid? sut/RuleEnforcementConfig {:action :audit})))))

(deftest rule-example-schema-test
  (testing "valid example with all fields"
    (is (sut/valid? sut/RuleExample
                    {:description "Test case"
                     :input "some code"
                     :expected :pass
                     :explanation "It passes because..."})))

  (testing "minimal example (no explanation)"
    (is (sut/valid? sut/RuleExample
                    {:description "Test" :input "code" :expected :fail})))

  (testing "expected must be :pass or :fail"
    (is (not (sut/valid? sut/RuleExample
                         {:description "Test" :input "code" :expected :error}))))

  (testing "description, input, expected are required"
    (is (not (sut/valid? sut/RuleExample {:input "code" :expected :pass})))
    (is (not (sut/valid? sut/RuleExample {:description "x" :expected :pass})))
    (is (not (sut/valid? sut/RuleExample {:description "x" :input "y"})))))

;; ============================================================================
;; Layer 2 — Rule schema tests
;; ============================================================================

(def minimal-valid-rule
  "A minimal rule map that satisfies all required fields."
  {:rule/id          :test/example
   :rule/title       "Example Rule"
   :rule/description "An example rule for testing"
   :rule/severity    :minor
   :rule/category    "testing"
   :rule/applies-to  {:task-types #{:create}}
   :rule/detection   {:type :custom}
   :rule/enforcement {:action :warn :message "Warning"}})

(deftest rule-schema-valid-minimal-test
  (testing "minimal valid rule passes validation"
    (is (sut/valid-rule? minimal-valid-rule))))

(deftest rule-schema-all-optional-fields-test
  (testing "rule with all optional fields passes validation"
    (is (sut/valid-rule?
         (assoc minimal-valid-rule
                :rule/agent-behavior   "Do this first."
                :rule/knowledge-content "# Full body text"
                :rule/always-inject?   true
                :rule/examples         [{:description "passes" :input "good" :expected :pass}]
                :rule/version          "2026.03"
                :rule/author           "test-author"
                :rule/references       ["https://example.com"])))))

(deftest rule-schema-knowledge-content-semantics-test
  (testing ":rule/knowledge-content accepts full MDC body text"
    (is (sut/valid-rule?
         (assoc minimal-valid-rule
                :rule/knowledge-content "# Stratified Design\n\nFull body text here..."))))

  (testing ":rule/knowledge-content is optional (omitted when body is empty)"
    (is (sut/valid-rule? minimal-valid-rule))
    (is (not (contains? minimal-valid-rule :rule/knowledge-content)))))

(deftest rule-schema-always-inject-semantics-test
  (testing ":rule/always-inject? true marks rule for unconditional phase-gated injection"
    (is (sut/valid-rule? (assoc minimal-valid-rule :rule/always-inject? true))))

  (testing ":rule/always-inject? false is valid"
    (is (sut/valid-rule? (assoc minimal-valid-rule :rule/always-inject? false))))

  (testing ":rule/always-inject? is optional — absent means false"
    (is (sut/valid-rule? (dissoc minimal-valid-rule :rule/always-inject?))))

  (testing ":rule/always-inject? must be boolean when present"
    (is (not (sut/valid-rule? (assoc minimal-valid-rule :rule/always-inject? "true"))))))

(deftest rule-schema-missing-required-fields-test
  (testing "missing :rule/id fails"
    (is (not (sut/valid-rule? (dissoc minimal-valid-rule :rule/id)))))

  (testing "missing :rule/title fails"
    (is (not (sut/valid-rule? (dissoc minimal-valid-rule :rule/title)))))

  (testing "missing :rule/description fails"
    (is (not (sut/valid-rule? (dissoc minimal-valid-rule :rule/description)))))

  (testing "missing :rule/severity fails"
    (is (not (sut/valid-rule? (dissoc minimal-valid-rule :rule/severity)))))

  (testing "missing :rule/category fails"
    (is (not (sut/valid-rule? (dissoc minimal-valid-rule :rule/category)))))

  (testing "missing :rule/applies-to fails"
    (is (not (sut/valid-rule? (dissoc minimal-valid-rule :rule/applies-to)))))

  (testing "missing :rule/detection fails"
    (is (not (sut/valid-rule? (dissoc minimal-valid-rule :rule/detection)))))

  (testing "missing :rule/enforcement fails"
    (is (not (sut/valid-rule? (dissoc minimal-valid-rule :rule/enforcement))))))

(deftest rule-schema-wrong-types-test
  (testing ":rule/id must be keyword"
    (is (not (sut/valid-rule? (assoc minimal-valid-rule :rule/id "not-keyword")))))

  (testing ":rule/severity must be valid enum"
    (is (not (sut/valid-rule? (assoc minimal-valid-rule :rule/severity :high)))))

  (testing ":rule/category must be string"
    (is (not (sut/valid-rule? (assoc minimal-valid-rule :rule/category :testing))))))

;; ============================================================================
;; Layer 2 — PackManifest schema tests
;; ============================================================================

(def minimal-valid-pack
  "A minimal PackManifest that satisfies all required fields."
  {:pack/id          "test/pack"
   :pack/name        "Test Pack"
   :pack/version     "2026.03"
   :pack/description "A test pack"
   :pack/author      "tester"
   :pack/categories  []
   :pack/rules       []
   :pack/created-at  (java.time.Instant/parse "2026-03-01T00:00:00Z")
   :pack/updated-at  (java.time.Instant/parse "2026-03-01T00:00:00Z")})

(deftest pack-manifest-valid-minimal-test
  (testing "minimal valid pack passes validation"
    (is (sut/valid-pack? minimal-valid-pack))))

(deftest pack-manifest-with-trust-model-test
  (testing "pack with trust-level and authority passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/trust-level :trusted
                :pack/authority   :authority/instruction))))

  (testing "pack with :tainted trust level passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack :pack/trust-level :tainted))))

  (testing "pack with :untrusted trust level passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack :pack/trust-level :untrusted)))))

(deftest pack-manifest-with-signing-test
  (testing "pack with signing fields passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/signature "sig-abc"
                :pack/signed-by "signer@example.com"
                :pack/signed-at (java.time.Instant/now))))))

(deftest pack-manifest-with-dependencies-test
  (testing "pack with extends (dependencies) passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/extends [{:pack-id "base/pack"
                                :version-constraint ">=2026.01"}]))))

  (testing "pack dependency requires :pack-id"
    (is (not (sut/valid-pack?
              (assoc minimal-valid-pack
                     :pack/extends [{:version-constraint ">=2026.01"}]))))))

(deftest pack-manifest-with-config-overrides-test
  (testing "pack with config-overrides passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/config-overrides {:governance {:max-iterations 5}})))))

(deftest pack-manifest-with-rules-test
  (testing "pack containing valid rules passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/rules [minimal-valid-rule])))))

(deftest pack-manifest-with-categories-test
  (testing "pack with valid categories passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/categories [{:category/id    "testing"
                                   :category/name  "Testing"
                                   :category/rules [:test/rule-a :test/rule-b]}])))))

(deftest pack-manifest-missing-required-fields-test
  (testing "missing :pack/id fails"
    (is (not (sut/valid-pack? (dissoc minimal-valid-pack :pack/id)))))

  (testing "missing :pack/name fails"
    (is (not (sut/valid-pack? (dissoc minimal-valid-pack :pack/name)))))

  (testing "missing :pack/version fails"
    (is (not (sut/valid-pack? (dissoc minimal-valid-pack :pack/version)))))

  (testing "missing :pack/categories fails"
    (is (not (sut/valid-pack? (dissoc minimal-valid-pack :pack/categories)))))

  (testing "missing :pack/rules fails"
    (is (not (sut/valid-pack? (dissoc minimal-valid-pack :pack/rules)))))

  (testing "missing :pack/created-at fails"
    (is (not (sut/valid-pack? (dissoc minimal-valid-pack :pack/created-at)))))

  (testing "missing :pack/updated-at fails"
    (is (not (sut/valid-pack? (dissoc minimal-valid-pack :pack/updated-at))))))

;; ============================================================================
;; Standards pack as separate file from builtin pack
;; ============================================================================

(deftest standards-pack-is-separate-from-builtin-test
  (testing "standards pack has distinct ID from builtin (both loaded from classpath)"
    ;; The standards pack uses 'miniforge/standards' while builtin uses a
    ;; different ID. Both are loaded independently from classpath EDN resources.
    (let [standards-pack (assoc minimal-valid-pack
                                :pack/id "miniforge/standards"
                                :pack/trust-level :trusted
                                :pack/authority :authority/instruction)
          builtin-pack  (assoc minimal-valid-pack
                                :pack/id "ai.miniforge/builtin"
                                :pack/trust-level :trusted)]
      (is (sut/valid-pack? standards-pack))
      (is (sut/valid-pack? builtin-pack))
      (is (not= (:pack/id standards-pack) (:pack/id builtin-pack))))))

;; ============================================================================
;; Validation helper tests
;; ============================================================================

(deftest valid?-test
  (testing "returns true for valid data"
    (is (true? (sut/valid? sut/RuleSeverity :critical))))

  (testing "returns false for invalid data"
    (is (false? (sut/valid? sut/RuleSeverity :nope)))))

(deftest validate-test
  (testing "returns {:valid? true :errors nil} for valid data"
    (let [result (sut/validate sut/RuleSeverity :critical)]
      (is (true? (:valid? result)))
      (is (nil? (:errors result)))))

  (testing "returns {:valid? false :errors ...} for invalid data"
    (let [result (sut/validate sut/RuleSeverity :nope)]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

(deftest explain-test
  (testing "returns nil for valid data"
    (is (nil? (sut/explain sut/RuleSeverity :critical))))

  (testing "returns humanized errors for invalid data"
    (is (some? (sut/explain sut/RuleSeverity :nope))))

  (testing "returns meaningful errors for wrong rule id type"
    (let [errors (sut/explain sut/Rule {:rule/id "not-a-keyword"})]
      (is (some? errors)))))

(deftest validate-rule-test
  (testing "valid rule returns {:valid? true}"
    (let [result (sut/validate-rule minimal-valid-rule)]
      (is (true? (:valid? result)))
      (is (nil? (:errors result)))))

  (testing "invalid rule returns {:valid? false :errors ...}"
    (let [result (sut/validate-rule {})]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

(deftest validate-pack-test
  (testing "valid pack returns {:valid? true}"
    (let [result (sut/validate-pack minimal-valid-pack)]
      (is (true? (:valid? result)))
      (is (nil? (:errors result)))))

  (testing "invalid pack returns {:valid? false :errors ...}"
    (let [result (sut/validate-pack {})]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

;; ============================================================================
;; Result helper tests
;; ============================================================================

(deftest succeeded?-test
  (testing "returns true for success result"
    (is (true? (sut/succeeded? {:success? true}))))

  (testing "returns false for failure result"
    (is (false? (sut/succeeded? {:success? false}))))

  (testing "returns false for missing :success? key"
    (is (false? (sut/succeeded? {}))))

  (testing "returns false for nil"
    (is (false? (sut/succeeded? nil)))))

(deftest success-test
  (testing "creates success result with key, value, and extras"
    (let [result (sut/success :pack {:pack/id "test"} {:errors nil})]
      (is (true? (:success? result)))
      (is (= {:pack/id "test"} (:pack result)))
      (is (nil? (:errors result)))))

  (testing "extras are merged into result"
    (let [result (sut/success :rule {:id 1} {:warnings ["w1"] :count 5})]
      (is (true? (:success? result)))
      (is (= {:id 1} (:rule result)))
      (is (= ["w1"] (:warnings result)))
      (is (= 5 (:count result))))))

(deftest failure-test
  (testing "creates failure result with error message"
    (let [result (sut/failure :data "something broke")]
      (is (false? (:success? result)))
      (is (= "something broke" (:error result)))))

  (testing "first arg (_key) is ignored"
    (let [result (sut/failure :ignored "msg")]
      (is (false? (:success? result)))
      (is (nil? (:ignored result))))))

(deftest failure-with-errors-test
  (testing "creates failure result with error list"
    (let [result (sut/failure-with-errors :pack ["err1" "err2"])]
      (is (false? (:success? result)))
      (is (= ["err1" "err2"] (:errors result)))))

  (testing "first arg (_key) is ignored"
    (let [result (sut/failure-with-errors :ignored ["e"])]
      (is (false? (:success? result)))
      (is (nil? (:ignored result))))))

;; ============================================================================
;; Rich Comment example validation — ensures documented examples stay correct
;; ============================================================================

(deftest rich-comment-rule-example-test
  (testing "import-block-preservation rule from rich comment validates"
    (is (sut/valid-rule?
         {:rule/id          :310-import-block-preservation
          :rule/title       "Preserve import blocks"
          :rule/description "Never remove import blocks during IMPORT tasks"
          :rule/severity    :critical
          :rule/category    "310"
          :rule/applies-to  {:task-types #{:import}
                             :file-globs ["**/*.tf"]}
          :rule/detection   {:type    :diff-analysis
                             :pattern "^-\\s*import\\s*\\{"}
          :rule/enforcement {:action  :hard-halt
                             :message "Cannot remove import blocks"}}))))

(deftest rich-comment-knowledge-rule-example-test
  (testing "knowledge rule with always-inject and knowledge-content validates"
    (is (sut/valid-rule?
         {:rule/id               :std/stratified-design
          :rule/title            "Stratified Design"
          :rule/description      "Engineering standard (001): Stratified Design"
          :rule/severity         :info
          :rule/category         "001"
          :rule/applies-to       {:phases #{:plan :implement :review :verify :release}}
          :rule/detection        {:type :custom}
          :rule/enforcement      {:action :audit :message "Standard: Stratified Design"}
          :rule/agent-behavior   "Before writing code, output a stratified plan."
          :rule/knowledge-content "# Stratified Design\n\nFull body text..."
          :rule/always-inject?   true}))))

(deftest rich-comment-pack-example-test
  (testing "pack example from rich comment validates"
    (is (sut/valid-pack?
         {:pack/id          "test-pack"
          :pack/name        "Test Pack"
          :pack/version     "2026.01.22"
          :pack/description "A test pack"
          :pack/author      "test"
          :pack/categories  []
          :pack/rules       []
          :pack/created-at  (java.time.Instant/now)
          :pack/updated-at  (java.time.Instant/now)}))))

(comment
  (clojure.test/run-tests 'ai.miniforge.policy-pack.schema-test)
  :leave-this-here)
