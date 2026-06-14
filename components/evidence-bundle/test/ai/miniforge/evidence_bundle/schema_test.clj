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

(ns ai.miniforge.evidence-bundle.schema-test
  "Tests for evidence bundle schema validation and pack promotion fields."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Schema Validation Tests

(deftest test-validate-schema-basic
  (testing "Schema validation accepts valid data"
    (let [valid-data {:constraint/type :pre
                      :constraint/description "Must exist"}
          result (schema/validate-schema schema/constraint-schema valid-data)]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "Schema validation rejects missing required keys"
    (let [invalid-data {:constraint/type :pre}
          result (schema/validate-schema schema/constraint-schema invalid-data)]
      (is (not (:valid? result)))
      (is (some #(= "Required key missing" (:error %)) (:errors result)))))

  (testing "Schema validation rejects present falsy values that fail validators"
    (let [invalid-data {:constraint/type :pre
                        :constraint/description false}
          result (schema/validate-schema schema/constraint-schema invalid-data)]
      (is (not (:valid? result)))
      (is (some #(= :constraint/description (:key %)) (:errors result))))))

;------------------------------------------------------------------------------ Layer 1
;; Pack Promotion Schema Tests

(deftest test-pack-promotion-schema-valid
  (testing "Valid pack promotion record passes schema validation"
    (let [promotion {:pack/id "test-pack-001"
                     :pack/type :knowledge
                     :from-trust :untrusted
                     :to-trust :trusted
                     :promoted-by "admin@example.com"
                     :promoted-at (java.time.Instant/now)
                     :promotion-policy "knowledge-safety"
                     :promotion-justification "passed knowledge-safety scans with no violations"
                     :pack-hash "sha256:abc123"
                     :pack-signature "sig456"}
          result (schema/validate-schema schema/pack-promotion-schema promotion)]
      (is (:valid? result)
          "Valid promotion record should pass schema validation")
      (is (empty? (:errors result))))))

(deftest test-pack-promotion-schema-required-fields
  (testing "Pack promotion schema requires all mandatory fields"
    (let [promotion {:pack/id "test-pack-001"
                     :pack/type :knowledge
                     :from-trust :untrusted
                     :to-trust :trusted
                     :promoted-by "system"
                     :promoted-at (java.time.Instant/now)
                     :promotion-policy "knowledge-safety"
                     ;; Missing :promotion-justification
                     :pack-hash "sha256:abc123"}
          result (schema/validate-schema schema/pack-promotion-schema promotion)]
      (is (not (:valid? result))
          "Missing promotion-justification should fail validation")
      (is (some #(and (= :promotion-justification (:key %))
                      (= "Required key missing" (:error %)))
                (:errors result))
          "Should report promotion-justification as missing"))))

(deftest test-pack-promotion-justification-field
  (testing "promotion-justification field is REQUIRED and must be string"
    (let [promotion-no-justification
          {:pack/id "pack-001"
           :pack/type :knowledge
           :from-trust :untrusted
           :to-trust :trusted
           :promoted-by "system"
           :promoted-at (java.time.Instant/now)
           :promotion-policy "knowledge-safety"
           :pack-hash "sha256:abc"}

          promotion-with-justification
          (assoc promotion-no-justification
                 :promotion-justification "passed knowledge-safety scans")

          result-without (schema/validate-schema schema/pack-promotion-schema
                                                 promotion-no-justification)
          result-with    (schema/validate-schema schema/pack-promotion-schema
                                                 promotion-with-justification)]

      (is (not (:valid? result-without))
          "Promotion without justification should fail")
      (is (:valid? result-with)
          "Promotion with justification should pass")
      (is (empty? (:errors result-with))))))

(deftest test-pack-promotion-trust-levels
  (testing "Trust levels must be valid keywords"
    (let [valid-promotion {:pack/id "pack-001"
                           :pack/type :knowledge
                           :from-trust :untrusted
                           :to-trust :trusted
                           :promoted-by "system"
                           :promoted-at (java.time.Instant/now)
                           :promotion-policy "knowledge-safety"
                           :promotion-justification "passed scans"
                           :pack-hash "sha256:abc"}
          invalid-promotion (assoc valid-promotion :from-trust :invalid-level)
          result-valid   (schema/validate-schema schema/pack-promotion-schema valid-promotion)
          result-invalid (schema/validate-schema schema/pack-promotion-schema invalid-promotion)]
      (is (:valid? result-valid)
          "Valid trust levels should pass")
      (is (not (:valid? result-invalid))
          "Invalid trust level should fail"))))

;------------------------------------------------------------------------------ Layer 2
;; Evidence Bundle Integration Tests

(deftest test-evidence-bundle-with-pack-promotions
  (testing "Evidence bundle accepts pack-promotions field"
    (let [bundle    (schema/create-evidence-bundle-template)
          promotion {:pack/id "pack-001"
                     :pack/type :knowledge
                     :from-trust :untrusted
                     :to-trust :trusted
                     :promoted-by "admin"
                     :promoted-at (java.time.Instant/now)
                     :promotion-policy "knowledge-safety"
                     :promotion-justification "manual review approved"
                     :pack-hash "sha256:test123"
                     :pack-signature ""}
          bundle+   (assoc bundle :evidence/pack-promotions [promotion])]
      (is (vector? (:evidence/pack-promotions bundle+)))
      (is (= 1 (count (:evidence/pack-promotions bundle+))))
      (is (= "manual review approved"
             (-> bundle+ :evidence/pack-promotions first :promotion-justification))))))

(deftest test-evidence-bundle-template-includes-pack-promotions
  (testing "Evidence bundle template initializes pack-promotions as empty vector"
    (let [bundle (schema/create-evidence-bundle-template)]
      (is (contains? bundle :evidence/pack-promotions)
          "Bundle template should include pack-promotions field")
      (is (vector? (:evidence/pack-promotions bundle))
          "pack-promotions should be a vector")
      (is (empty? (:evidence/pack-promotions bundle))
          "pack-promotions should start empty"))))

;------------------------------------------------------------------------------ Layer 3
;; Justification Content Validation Tests

(deftest test-justification-content-examples
  (testing "Common justification patterns are valid"
    (let [justifications ["passed knowledge-safety scans with no violations"
                          "manual review approved by security team"
                          "verified signature from trusted key 0x123ABC"
                          "meets all policy compliance requirements"
                          "automated validation completed successfully"]
          make-promotion (fn [j]
                           {:pack/id "pack-001"
                            :pack/type :knowledge
                            :from-trust :untrusted
                            :to-trust :trusted
                            :promoted-by "system"
                            :promoted-at (java.time.Instant/now)
                            :promotion-policy "knowledge-safety"
                            :promotion-justification j
                            :pack-hash "sha256:test"
                            :pack-signature ""})
          results (map #(schema/validate-schema schema/pack-promotion-schema
                                                (make-promotion %))
                       justifications)]
      (is (every? :valid? results)
          "All common justification patterns should be valid")
      (is (every? #(empty? (:errors %)) results)))))

(deftest test-empty-justification-invalid
  (testing "Empty justification string passes schema (content validated at business layer)"
    (let [promotion {:pack/id "pack-001"
                     :pack/type :knowledge
                     :from-trust :untrusted
                     :to-trust :trusted
                     :promoted-by "system"
                     :promoted-at (java.time.Instant/now)
                     :promotion-policy "knowledge-safety"
                     :promotion-justification ""
                     :pack-hash "sha256:test"
                     :pack-signature ""}
          result (schema/validate-schema schema/pack-promotion-schema promotion)]
      (is (:valid? result)
          "Schema validation passes for empty string (business logic should reject)"))))

(comment
  ;; Run all tests
  (clojure.test/run-tests)

  ;; Run specific test
  (test-pack-promotion-justification-field)

  :leave-this-here)

;------------------------------------------------------------------------------ Layer 4
;; Compliance Metadata Tests

(deftest test-create-evidence-bundle-template-compliance-defaults
  (testing "Template emits all six compliance fields with correct defaults"
    (let [bundle (schema/create-evidence-bundle-template)]
      (testing ":evidence/data-classification defaults to :internal"
        (is (= :internal (:evidence/data-classification bundle))))
      (testing ":evidence/contains-pii? defaults to false"
        (is (false? (:evidence/contains-pii? bundle))))
      (testing ":evidence/retention-policy is a map with all required keys"
        (let [rp (:evidence/retention-policy bundle)]
          (is (map? rp))
          (is (contains? rp :retain-days))
          (is (contains? rp :auto-delete?))
          (is (contains? rp :legal-hold?))))
      (testing ":evidence/retention-policy :auto-delete? is true"
        (is (true? (get-in bundle [:evidence/retention-policy :auto-delete?]))))
      (testing ":evidence/retention-policy :legal-hold? is false"
        (is (false? (get-in bundle [:evidence/retention-policy :legal-hold?]))))
      (testing ":evidence/regulatory-tags defaults to empty set"
        (is (= #{} (:evidence/regulatory-tags bundle))))
      (testing ":evidence/created-by defaults to system principal"
        (is (= schema/default-created-by-principal (:evidence/created-by bundle))))
      (testing ":evidence/access-log defaults to empty vector"
        (is (= [] (:evidence/access-log bundle)))))))

(deftest test-template-retention-days-from-named-constant
  (testing ":retain-days in template comes from default-retention-days constant, not a magic literal"
    (let [bundle (schema/create-evidence-bundle-template)
          retain-days (get-in bundle [:evidence/retention-policy :retain-days])]
      (is (= schema/default-retention-days retain-days)
          "retain-days must equal the named constant, not a different value"))))

(deftest test-evidence-bundle-schema-data-classification-valid
  (testing "evidence-bundle-schema accepts known data-classification values"
    (doseq [cls schema/data-classifications]
      (let [bundle (-> (schema/create-evidence-bundle-template)
                       (assoc :evidence-bundle/workflow-id (random-uuid))
                       (assoc :evidence/data-classification cls))
            result (schema/validate-schema schema/evidence-bundle-schema bundle)]
        (is (:valid? result)
            (str "Classification " cls " should be valid"))))))

(deftest test-evidence-bundle-schema-data-classification-invalid
  (testing "evidence-bundle-schema rejects unknown data-classification value"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/data-classification :top-secret))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result))
          "Unknown classification :top-secret should fail validation")
      (is (some #(= :evidence/data-classification (:key %)) (:errors result))
          "Error should name :evidence/data-classification"))))

(deftest test-evidence-bundle-schema-regulatory-tags-valid
  (testing "evidence-bundle-schema accepts known regulatory tags"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/regulatory-tags #{:gdpr :sox}))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest test-evidence-bundle-schema-regulatory-tags-invalid
  (testing "evidence-bundle-schema rejects unknown regulatory tags"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/regulatory-tags #{:gdpr :unknown-framework}))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result))
          "Bundle with unknown regulatory tag should fail validation")
      (is (some #(= :evidence/regulatory-tags (:key %)) (:errors result))
          "Error should name :evidence/regulatory-tags"))))

(deftest test-evidence-bundle-schema-backwards-compatible
  (testing "Existing bundles without new compliance keys still pass validation"
    (let [legacy-bundle {:evidence-bundle/id (random-uuid)
                         :evidence-bundle/workflow-id (random-uuid)
                         :evidence-bundle/created-at (java.time.Instant/now)
                         :evidence-bundle/version "1.0.0"
                         :evidence/intent {}
                         :evidence/policy-checks []
                         :evidence/outcome {}}
          result (schema/validate-schema schema/evidence-bundle-schema legacy-bundle)]
      (is (:valid? result)
          "Legacy bundle without compliance fields should pass schema validation")
      (is (empty? (:errors result))))))

(deftest test-retention-policy-schema-validates-sub-document
  (testing "retention-policy-schema accepts valid retention policy"
    (let [valid-policy {:retain-days 30 :auto-delete? true :legal-hold? false}
          result (schema/validate-schema schema/retention-policy-schema valid-policy)]
      (is (:valid? result))))
  (testing "retention-policy-schema rejects missing required keys"
    (let [invalid-policy {:retain-days 30 :auto-delete? true}
          result (schema/validate-schema schema/retention-policy-schema invalid-policy)]
      (is (not (:valid? result)))
      (is (some #(= :legal-hold? (:key %)) (:errors result))))))

(deftest test-evidence-bundle-schema-wires-retention-policy
  (testing "evidence-bundle-schema validates :evidence/retention-policy via retention-policy-schema"
    (let [bad-retention {:retain-days -1 :auto-delete? true :legal-hold? false}
          bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/retention-policy bad-retention))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result))
          "Bundle with invalid retention policy should fail validation"))))

(deftest test-evidence-bundle-schema-wires-access-log
  (testing "evidence-bundle-schema validates :evidence/access-log entries via access-log-entry-schema"
    (let [bad-entry {:access-log/principal "alice"
                     :access-log/timestamp (java.time.Instant/now)}
          ;; missing :access-log/action
          bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/access-log [bad-entry]))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result))
          "Bundle with invalid access-log entry should fail validation")))
  (testing "evidence-bundle-schema requires :evidence/access-log to be a vector"
    (let [bad-access-log {:access-log/principal "alice"
                          :access-log/action :read
                          :access-log/timestamp (java.time.Instant/now)}
          bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/access-log bad-access-log))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result))
          "Bundle with non-vector access-log should fail validation")
      (is (some #(= :evidence/access-log (:key %)) (:errors result))))))

;------------------------------------------------------------------------------ Layer 5
;; Enum Membership Tests

(deftest test-data-classifications-enum-contains-expected-members
  (testing "data-classifications set contains all four N6-specified levels"
    (is (contains? schema/data-classifications :public))
    (is (contains? schema/data-classifications :internal))
    (is (contains? schema/data-classifications :confidential))
    (is (contains? schema/data-classifications :restricted)))
  (testing "data-classifications set has no extra unexpected members"
    (is (= 4 (count schema/data-classifications)))))

(deftest test-regulatory-tag-values-enum-contains-expected-members
  (testing "regulatory-tag-values set contains all four N6-specified frameworks"
    (is (contains? schema/regulatory-tag-values :gdpr))
    (is (contains? schema/regulatory-tag-values :hipaa))
    (is (contains? schema/regulatory-tag-values :sox))
    (is (contains? schema/regulatory-tag-values :pci)))
  (testing "regulatory-tag-values set has no extra unexpected members"
    (is (= 4 (count schema/regulatory-tag-values)))))

;------------------------------------------------------------------------------ Layer 6
;; access-log-entry-schema Direct Tests

(deftest test-access-log-entry-schema-valid-entry
  (testing "access-log-entry-schema accepts a correctly shaped entry"
    (let [entry  {:access-log/principal "alice@example.com"
                  :access-log/action    :read
                  :access-log/timestamp (java.time.Instant/now)}
          result (schema/validate-schema schema/access-log-entry-schema entry)]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "access-log-entry-schema accepts entry with optional :access-log/reason"
    (let [entry  {:access-log/principal "auditor"
                  :access-log/action    :export
                  :access-log/timestamp (java.time.Instant/now)
                  :access-log/reason    "quarterly compliance audit"}
          result (schema/validate-schema schema/access-log-entry-schema entry)]
      (is (:valid? result)))))

(deftest test-access-log-entry-schema-invalid-missing-action
  (testing "access-log-entry-schema rejects entry missing :access-log/action"
    (let [entry  {:access-log/principal "alice@example.com"
                  :access-log/timestamp (java.time.Instant/now)}
          result (schema/validate-schema schema/access-log-entry-schema entry)]
      (is (not (:valid? result)))
      (is (some #(= :access-log/action (:key %)) (:errors result))))))

(deftest test-access-log-entry-schema-invalid-missing-principal
  (testing "access-log-entry-schema rejects entry missing :access-log/principal"
    (let [entry  {:access-log/action    :read
                  :access-log/timestamp (java.time.Instant/now)}
          result (schema/validate-schema schema/access-log-entry-schema entry)]
      (is (not (:valid? result)))
      (is (some #(= :access-log/principal (:key %)) (:errors result))))))

(deftest test-access-log-entry-schema-invalid-missing-timestamp
  (testing "access-log-entry-schema rejects entry missing :access-log/timestamp"
    (let [entry  {:access-log/principal "alice@example.com"
                  :access-log/action    :read}
          result (schema/validate-schema schema/access-log-entry-schema entry)]
      (is (not (:valid? result)))
      (is (some #(= :access-log/timestamp (:key %)) (:errors result))))))

;------------------------------------------------------------------------------ Layer 7
;; evidence-bundle-schema — bundles WITH new optional compliance fields

(deftest test-evidence-bundle-schema-accepts-bundle-with-all-new-fields
  (testing "evidence-bundle-schema accepts a bundle carrying all six new compliance fields"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     ;; All six new compliance fields explicitly set
                     (assoc :evidence/data-classification :confidential)
                     (assoc :evidence/contains-pii? true)
                     (assoc :evidence/retention-policy {:retain-days  365
                                                        :auto-delete? false
                                                        :legal-hold?  true})
                     (assoc :evidence/regulatory-tags #{:gdpr :hipaa})
                     (assoc :evidence/created-by "operator-alice")
                     (assoc :evidence/access-log
                            [{:access-log/principal "auditor"
                              :access-log/action    :read
                              :access-log/timestamp (java.time.Instant/now)}]))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (:valid? result)
          (str "Bundle with all new compliance fields should pass; errors: "
               (:errors result))))))

(deftest test-evidence-bundle-schema-accepts-each-data-classification
  (testing "evidence-bundle-schema accepts every member of data-classifications"
    (doseq [cls schema/data-classifications]
      (let [bundle (-> (schema/create-evidence-bundle-template)
                       (assoc :evidence-bundle/workflow-id (random-uuid))
                       (assoc :evidence/data-classification cls))
            result (schema/validate-schema schema/evidence-bundle-schema bundle)]
        (is (:valid? result)
            (str "Classification " cls " should be accepted by evidence-bundle-schema"))))))

(deftest test-evidence-bundle-schema-accepts-regulatory-tags-set
  (testing "evidence-bundle-schema accepts a bundle with regulatory-tags populated"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/regulatory-tags #{:sox :pci}))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (:valid? result)
          "Bundle with regulatory-tags should pass schema validation"))))

(deftest test-evidence-bundle-schema-rejects-invalid-data-classification
  (testing "evidence-bundle-schema rejects :evidence/data-classification outside the enum"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/data-classification :ultra-secret))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result)))
      (is (some #(= :evidence/data-classification (:key %)) (:errors result))))))
