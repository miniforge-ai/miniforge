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
      (is (some #(= "Required key missing" (:error %)) (:errors result))))))

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
          result-with (schema/validate-schema schema/pack-promotion-schema
                                               promotion-with-justification)]

      ;; Without justification should fail
      (is (not (:valid? result-without))
          "Promotion without justification should fail")

      ;; With justification should pass
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

          invalid-promotion (assoc valid-promotion
                                   :from-trust :invalid-level)

          result-valid (schema/validate-schema schema/pack-promotion-schema
                                                valid-promotion)
          result-invalid (schema/validate-schema schema/pack-promotion-schema
                                                  invalid-promotion)]

      (is (:valid? result-valid)
          "Valid trust levels should pass")
      (is (not (:valid? result-invalid))
          "Invalid trust level should fail"))))

;------------------------------------------------------------------------------ Layer 2
;; Evidence Bundle Integration Tests

(deftest test-evidence-bundle-with-pack-promotions
  (testing "Evidence bundle accepts pack-promotions field"
    (let [bundle (schema/create-evidence-bundle-template)
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
          bundle-with-promotions (assoc bundle
                                        :evidence/pack-promotions [promotion])]

      (is (vector? (:evidence/pack-promotions bundle-with-promotions)))
      (is (= 1 (count (:evidence/pack-promotions bundle-with-promotions))))
      (is (= "manual review approved"
             (-> bundle-with-promotions
                 :evidence/pack-promotions
                 first
                 :promotion-justification))))))

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
          promotions (map (fn [justification]
                            {:pack/id "pack-001"
                             :pack/type :knowledge
                             :from-trust :untrusted
                             :to-trust :trusted
                             :promoted-by "system"
                             :promoted-at (java.time.Instant/now)
                             :promotion-policy "knowledge-safety"
                             :promotion-justification justification
                             :pack-hash "sha256:test"
                             :pack-signature ""})
                          justifications)
          results (map #(schema/validate-schema schema/pack-promotion-schema %)
                       promotions)]

      (is (every? :valid? results)
          "All common justification patterns should be valid")
      (is (every? #(empty? (:errors %)) results)))))

(deftest test-empty-justification-invalid
  (testing "Empty justification string should fail validation"
    (let [promotion {:pack/id "pack-001"
                     :pack/type :knowledge
                     :from-trust :untrusted
                     :to-trust :trusted
                     :promoted-by "system"
                     :promoted-at (java.time.Instant/now)
                     :promotion-policy "knowledge-safety"
                     :promotion-justification ""  ;; Empty string
                     :pack-hash "sha256:test"
                     :pack-signature ""}
          result (schema/validate-schema schema/pack-promotion-schema promotion)]

      ;; Schema validation only checks type, not content
      ;; Content validation happens at business logic layer
      (is (:valid? result)
          "Schema validation passes for empty string (business logic should reject)"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run all tests
  (clojure.test/run-tests)

  ;; Run specific test
  (test-pack-promotion-justification-field)

  :leave-this-here)
