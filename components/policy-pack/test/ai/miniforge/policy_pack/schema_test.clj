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
(ns ai.miniforge.policy-pack.schema-test
  "Unit tests for policy-pack Malli schemas, validation helpers, and result helpers.

   Split across the same three namespaces schema.clj itself split into
   (Wave 2, SL003): `schema-types` (enums + component schemas), `schema-
   validation` (generic valid?/validate/explain + result helpers), and
   `schema` (Rule/PackManifest + their valid-*?/validate-* wrappers).

   Covers:
   - Enum definitions and base type schemas (schema-types, aliased `types`)
   - Rule component schemas: applicability, detection, enforcement, example
     (schema-types, aliased `types`)
   - Rule and PackManifest schemas (schema, aliased `sut`)
   - Validation helpers: valid?, validate, explain (schema-validation,
     aliased `sv`)
   - Convenience wrappers: valid-rule?, validate-rule, valid-pack?,
     validate-pack (schema, aliased `sut`)
   - Result helpers: succeeded?, success, failure, failure-with-errors
     (schema-validation, aliased `sv`)"
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.policy-pack.schema :as sut]
   [ai.miniforge.policy-pack.schema-types :as types]
   [ai.miniforge.policy-pack.schema-validation :as sv]))

;------------------------------------------------------------------------------ Layer 0

;; ============================================================================
;; Enum definition tests
;; ============================================================================
(deftest ^{:stratum 0} rule-severities-test
  (testing "rule-severities is the canonical five-keyword scale, descending"
    (is (= [:critical :high :medium :low :info] types/rule-severities))
    (is (= 5 (count types/rule-severities)))))

(deftest ^{:stratum 0} enforcement-actions-test
  (testing "enforcement-actions ordered from strictest to most lenient"
    (is (= [:hard-halt :require-approval :warn :audit] types/enforcement-actions))
    (is (= 4 (count types/enforcement-actions)))))

(deftest ^{:stratum 0} detection-types-test
  (testing "detection-types has seven detection mechanisms"
    (is (= [:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis :custom :capability]
           types/detection-types))
    (is (= 7 (count types/detection-types)))))

(deftest ^{:stratum 0} task-types-test
  (testing "task-types has five task operations"
    (is (= [:create :import :modify :delete :migrate] types/task-types))
    (is (= 5 (count types/task-types)))))

(deftest ^{:stratum 0} repo-types-test
  (testing "repo-types has five repository types"
    (is (= [:terraform-module :terraform-live :kubernetes :argocd :application]
           types/repo-types))
    (is (= 5 (count types/repo-types)))))

(deftest ^{:stratum 0} approver-types-test
  (testing "approver-types has three approver kinds"
    (is (= [:human :senior-engineer :security] types/approver-types))
    (is (= 3 (count types/approver-types)))))

;; ============================================================================
;; Enum schema validation tests
;; ============================================================================
(deftest ^{:stratum 0} rule-severity-schema-test
  (testing "valid severity keywords pass"
    (doseq [sev [:critical :high :medium :low :info]]
      (is (sv/valid? types/RuleSeverity sev)
          (str sev " should be valid"))))

  (testing "invalid values rejected — incl. the legacy :major/:minor"
    (are [v] (not (sv/valid? types/RuleSeverity v))
      :warning :error :major :minor "critical" nil 42)))

(deftest ^{:stratum 0} rule-enforcement-schema-test
  (testing "valid enforcement actions pass"
    (doseq [action [:hard-halt :require-approval :warn :audit]]
      (is (sv/valid? types/RuleEnforcement action))))

  (testing "invalid enforcement actions rejected"
    (are [v] (not (sv/valid? types/RuleEnforcement v))
      :block :allow :skip "hard-halt" nil)))

(deftest ^{:stratum 0} detection-type-schema-test
  (testing "valid detection types pass"
    (doseq [dt [:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis :custom]]
      (is (sv/valid? types/DetectionType dt))))

  (testing "invalid detection types rejected"
    (is (not (sv/valid? types/DetectionType :regex)))
    (is (not (sv/valid? types/DetectionType "custom")))))

(deftest ^{:stratum 0} task-type-schema-test
  (testing "valid task types pass"
    (doseq [tt [:create :import :modify :delete :migrate]]
      (is (sv/valid? types/TaskType tt))))

  (testing "invalid task types rejected"
    (is (not (sv/valid? types/TaskType :update)))
    (is (not (sv/valid? types/TaskType :read)))))

(deftest ^{:stratum 0} repo-type-schema-test
  (testing "valid repo types pass"
    (doseq [rt [:terraform-module :terraform-live :kubernetes :argocd :application]]
      (is (sv/valid? types/RepoType rt))))

  (testing "invalid repo types rejected"
    (is (not (sv/valid? types/RepoType :github)))
    (is (not (sv/valid? types/RepoType :docker)))))

(deftest ^{:stratum 0} approver-type-schema-test
  (testing "valid approver types pass"
    (doseq [at [:human :senior-engineer :security]]
      (is (sv/valid? types/ApproverType at))))

  (testing "invalid approver types rejected"
    (is (not (sv/valid? types/ApproverType :bot)))
    (is (not (sv/valid? types/ApproverType :manager)))))

(deftest ^{:stratum 0} trust-level-schema-test
  (testing "valid trust levels pass"
    (doseq [tl [:tainted :untrusted :trusted]]
      (is (sv/valid? types/TrustLevel tl))))

  (testing "invalid trust levels rejected"
    (is (not (sv/valid? types/TrustLevel :verified)))
    (is (not (sv/valid? types/TrustLevel :unknown)))))

(deftest ^{:stratum 0} authority-channel-schema-test
  (testing "valid authority channels pass"
    (is (sv/valid? types/AuthorityChannel :authority/instruction))
    (is (sv/valid? types/AuthorityChannel :authority/data)))

  (testing "invalid authority channels rejected"
    (is (not (sv/valid? types/AuthorityChannel :authority/reference)))
    (is (not (sv/valid? types/AuthorityChannel :instruction)))))

;; ============================================================================
;; Component schema tests
;; ============================================================================
(deftest ^{:stratum 0} rule-applicability-schema-test
  (testing "empty map is valid (all fields optional)"
    (is (sv/valid? types/RuleApplicability {})))

  (testing "full applicability map is valid"
    (is (sv/valid? types/RuleApplicability
                    {:task-types #{:create :modify}
                     :file-globs ["**/*.tf"]
                     :resource-patterns ["aws_s3_bucket.*"]
                     :repo-types #{:terraform-module}
                     :phases #{:plan :implement}})))

  (testing "task-types must be a set of TaskType"
    (is (not (sv/valid? types/RuleApplicability {:task-types #{:unknown}}))))

  (testing "file-globs must be a vector of strings"
    (is (not (sv/valid? types/RuleApplicability {:file-globs [42]}))))

  (testing "resource-patterns accepts strings and regex patterns"
    (is (sv/valid? types/RuleApplicability {:resource-patterns ["pattern"]}))
    (is (sv/valid? types/RuleApplicability {:resource-patterns [#"regex"]})))

  (testing "phases must be a set of keywords"
    (is (sv/valid? types/RuleApplicability {:phases #{:plan :review :implement}}))))

(deftest ^{:stratum 0} rule-detection-schema-test
  (testing "minimal detection: type only"
    (is (sv/valid? types/RuleDetection {:type :custom})))

  (testing "detection with string pattern"
    (is (sv/valid? types/RuleDetection {:type :diff-analysis
                                       :pattern "^-\\s*import"})))

  (testing "detection with regex pattern"
    (is (sv/valid? types/RuleDetection {:type :content-scan
                                       :pattern #"secret.*key"})))

  (testing "detection with multiple patterns"
    (is (sv/valid? types/RuleDetection {:type :content-scan
                                       :patterns ["pattern-a" #"pattern-b"]})))

  (testing "detection with context-lines"
    (is (sv/valid? types/RuleDetection {:type :diff-analysis
                                       :context-lines 3})))

  (testing "detection with custom-fn symbol"
    (is (sv/valid? types/RuleDetection {:type :custom
                                       :custom-fn 'my.ns/detect-fn})))

  (testing "detection with capability keyword"
    (is (sv/valid? types/RuleDetection {:type :capability
                                       :capability :lint})))

  (testing "type is required"
    (is (not (sv/valid? types/RuleDetection {}))))

  (testing "context-lines must be positive integer"
    (is (not (sv/valid? types/RuleDetection {:type :custom :context-lines 0})))
    (is (not (sv/valid? types/RuleDetection {:type :custom :context-lines -1})))))

(deftest ^{:stratum 0} rule-enforcement-config-schema-test
  (testing "minimal enforcement: action + message"
    (is (sv/valid? types/RuleEnforcementConfig
                    {:action :hard-halt :message "Stop!"})))

  (testing "enforcement with remediation"
    (is (sv/valid? types/RuleEnforcementConfig
                    {:action :warn
                     :message "Warning"
                     :remediation "Fix by doing X"})))

  (testing "enforcement with approvers"
    (is (sv/valid? types/RuleEnforcementConfig
                    {:action :require-approval
                     :message "Needs approval"
                     :approvers [:human :security]})))

  (testing "action is required"
    (is (not (sv/valid? types/RuleEnforcementConfig {:message "oops"}))))

  (testing "message is required"
    (is (not (sv/valid? types/RuleEnforcementConfig {:action :audit})))))

(deftest ^{:stratum 0} rule-example-schema-test
  (testing "valid example with all fields"
    (is (sv/valid? types/RuleExample
                    {:description "Test case"
                     :input "some code"
                     :expected :pass
                     :explanation "It passes because..."})))

  (testing "minimal example (no explanation)"
    (is (sv/valid? types/RuleExample
                    {:description "Test" :input "code" :expected :fail})))

  (testing "expected must be :pass or :fail"
    (is (not (sv/valid? types/RuleExample
                         {:description "Test" :input "code" :expected :error}))))

  (testing "description, input, expected are required"
    (is (not (sv/valid? types/RuleExample {:input "code" :expected :pass})))
    (is (not (sv/valid? types/RuleExample {:description "x" :expected :pass})))
    (is (not (sv/valid? types/RuleExample {:description "x" :input "y"})))))

;; ============================================================================
;; Rule schema tests
;; ============================================================================
(def ^{:stratum 0} minimal-valid-rule
  "A minimal rule map that satisfies all required fields."
  {:rule/id          :test/example
   :rule/title       "Example Rule"
   :rule/description "An example rule for testing"
   :rule/severity    :low
   :rule/category    "testing"
   :rule/applies-to  {:task-types #{:create}}
   :rule/detection   {:type :custom}
   :rule/enforcement {:action :warn :message "Warning"}})

;; ============================================================================
;; PackManifest schema tests
;; ============================================================================
(def ^{:stratum 0} minimal-valid-pack
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

;; ============================================================================
;; Validation helper tests
;; ============================================================================
(deftest ^{:stratum 0} valid?-test
  (testing "returns true for valid data"
    (is (true? (sv/valid? types/RuleSeverity :critical))))

  (testing "returns false for invalid data"
    (is (false? (sv/valid? types/RuleSeverity :nope)))))

(deftest ^{:stratum 0} validate-test
  (testing "returns {:valid? true :errors nil} for valid data"
    (let [result (sv/validate types/RuleSeverity :critical)]
      (is (true? (:valid? result)))
      (is (nil? (:errors result)))))

  (testing "returns {:valid? false :errors ...} for invalid data"
    (let [result (sv/validate types/RuleSeverity :nope)]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

(deftest ^{:stratum 0} explain-test
  (testing "returns nil for valid data"
    (is (nil? (sv/explain types/RuleSeverity :critical))))

  (testing "returns humanized errors for invalid data"
    (is (some? (sv/explain types/RuleSeverity :nope))))

  (testing "returns meaningful errors for wrong rule id type"
    (let [errors (sv/explain sut/Rule {:rule/id "not-a-keyword"})]
      (is (some? errors)))))

;; ============================================================================
;; Result helper tests
;; ============================================================================
(deftest ^{:stratum 0} succeeded?-test
  (testing "returns true for success result"
    (is (true? (sv/succeeded? {:success? true}))))

  (testing "returns false for failure result"
    (is (false? (sv/succeeded? {:success? false}))))

  (testing "returns false for missing :success? key"
    (is (false? (sv/succeeded? {}))))

  (testing "returns false for nil"
    (is (false? (sv/succeeded? nil)))))

(deftest ^{:stratum 0} success-test
  (testing "creates success result with key, value, and extras"
    (let [result (sv/success :pack {:pack/id "test"} {:errors nil})]
      (is (true? (:success? result)))
      (is (= {:pack/id "test"} (:pack result)))
      (is (nil? (:errors result)))))

  (testing "extras are merged into result"
    (let [result (sv/success :rule {:id 1} {:warnings ["w1"] :count 5})]
      (is (true? (:success? result)))
      (is (= {:id 1} (:rule result)))
      (is (= ["w1"] (:warnings result)))
      (is (= 5 (:count result))))))

(deftest ^{:stratum 0} failure-test
  (testing "creates failure result with error message"
    (let [result (sv/failure :data "something broke")]
      (is (false? (:success? result)))
      (is (= "something broke" (:error result)))))

  (testing "first arg (_key) is ignored"
    (let [result (sv/failure :ignored "msg")]
      (is (false? (:success? result)))
      (is (nil? (:ignored result))))))

(deftest ^{:stratum 0} failure-with-errors-test
  (testing "creates failure result with error list"
    (let [result (sv/failure-with-errors :pack ["err1" "err2"])]
      (is (false? (:success? result)))
      (is (= ["err1" "err2"] (:errors result)))))

  (testing "first arg (_key) is ignored"
    (let [result (sv/failure-with-errors :ignored ["e"])]
      (is (false? (:success? result)))
      (is (nil? (:ignored result))))))

;; ============================================================================
;; Rich Comment example validation — ensures documented examples stay correct
;; ============================================================================
(deftest ^{:stratum 0} rich-comment-rule-example-test
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

(deftest ^{:stratum 0} rich-comment-knowledge-rule-example-test
  (testing "knowledge rule with always-inject and knowledge-content validates"
    (is (sut/valid-rule?
         {:rule/id               :std/stratified-design
          :rule/title            "Stratified Design"
          :rule/description      "Engineering standard (001): Stratified Design"
          :rule/severity         :high
          :rule/category         "001"
          :rule/applies-to       {:phases #{:plan :implement :review :verify :release}}
          :rule/detection        {:type :custom}
          :rule/enforcement      {:action :warn :message "Standard: Stratified Design"}
          :rule/agent-behavior   "Before writing code, output a stratified plan."
          :rule/knowledge-content "# Stratified Design\n\nFull body text..."
          :rule/always-inject?   true}))))

(deftest ^{:stratum 0} rich-comment-pack-example-test
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

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} rule-schema-valid-minimal-test
  (testing "minimal valid rule passes validation"
    (is (sut/valid-rule? minimal-valid-rule))))

(deftest ^{:stratum 1} rule-schema-all-optional-fields-test
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

(deftest ^{:stratum 1} rule-schema-knowledge-content-semantics-test
  (testing ":rule/knowledge-content accepts full MDC body text"
    (is (sut/valid-rule?
         (assoc minimal-valid-rule
                :rule/knowledge-content "# Stratified Design\n\nFull body text here..."))))

  (testing ":rule/knowledge-content is optional (omitted when body is empty)"
    (is (sut/valid-rule? minimal-valid-rule))
    (is (not (contains? minimal-valid-rule :rule/knowledge-content)))))

(deftest ^{:stratum 1} rule-schema-always-inject-semantics-test
  (testing ":rule/always-inject? true marks rule for unconditional phase-gated injection"
    (is (sut/valid-rule? (assoc minimal-valid-rule :rule/always-inject? true))))

  (testing ":rule/always-inject? false is valid"
    (is (sut/valid-rule? (assoc minimal-valid-rule :rule/always-inject? false))))

  (testing ":rule/always-inject? is optional — absent means false"
    (is (sut/valid-rule? (dissoc minimal-valid-rule :rule/always-inject?))))

  (testing ":rule/always-inject? must be boolean when present"
    (is (not (sut/valid-rule? (assoc minimal-valid-rule :rule/always-inject? "true"))))))

(deftest ^{:stratum 1} rule-schema-missing-required-fields-test
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

(deftest ^{:stratum 1} rule-schema-wrong-types-test
  (testing ":rule/id must be keyword"
    (is (not (sut/valid-rule? (assoc minimal-valid-rule :rule/id "not-keyword")))))

  (testing ":rule/severity must be valid enum"
    (is (not (sut/valid-rule? (assoc minimal-valid-rule :rule/severity :major)))))

  (testing ":rule/category must be string"
    (is (not (sut/valid-rule? (assoc minimal-valid-rule :rule/category :testing))))))

(deftest ^{:stratum 1} pack-manifest-valid-minimal-test
  (testing "minimal valid pack passes validation"
    (is (sut/valid-pack? minimal-valid-pack))))

(deftest ^{:stratum 1} pack-manifest-with-trust-model-test
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

(deftest ^{:stratum 1} pack-manifest-with-signing-test
  (testing "pack with signing fields passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/signature "sig-abc"
                :pack/signed-by "signer@example.com"
                :pack/signed-at (java.time.Instant/now))))))

(deftest ^{:stratum 1} pack-manifest-with-dependencies-test
  (testing "pack with extends (dependencies) passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/extends [{:pack-id "base/pack"
                                :version-constraint ">=2026.01"}]))))

  (testing "pack dependency requires :pack-id"
    (is (not (sut/valid-pack?
              (assoc minimal-valid-pack
                     :pack/extends [{:version-constraint ">=2026.01"}]))))))

(deftest ^{:stratum 1} pack-manifest-with-config-overrides-test
  (testing "pack with config-overrides passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/config-overrides {:governance {:max-iterations 5}})))))

(deftest ^{:stratum 1} pack-manifest-with-rules-test
  (testing "pack containing valid rules passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/rules [minimal-valid-rule])))))

(deftest ^{:stratum 1} pack-manifest-with-categories-test
  (testing "pack with valid categories passes"
    (is (sut/valid-pack?
         (assoc minimal-valid-pack
                :pack/categories [{:category/id    "testing"
                                   :category/name  "Testing"
                                   :category/rules [:test/rule-a :test/rule-b]}])))))

(deftest ^{:stratum 1} pack-manifest-missing-required-fields-test
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
(deftest ^{:stratum 1} standards-pack-is-separate-from-builtin-test
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

(deftest ^{:stratum 1} validate-rule-test
  (testing "valid rule returns {:valid? true}"
    (let [result (sut/validate-rule minimal-valid-rule)]
      (is (true? (:valid? result)))
      (is (nil? (:errors result)))))

  (testing "invalid rule returns {:valid? false :errors ...}"
    (let [result (sut/validate-rule {})]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

(deftest ^{:stratum 1} validate-pack-test
  (testing "valid pack returns {:valid? true}"
    (let [result (sut/validate-pack minimal-valid-pack)]
      (is (true? (:valid? result)))
      (is (nil? (:errors result)))))

  (testing "invalid pack returns {:valid? false :errors ...}"
    (let [result (sut/validate-pack {})]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.policy-pack.schema-test)
  :leave-this-here)
