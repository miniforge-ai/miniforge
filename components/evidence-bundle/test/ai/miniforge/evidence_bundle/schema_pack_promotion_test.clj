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

(ns ai.miniforge.evidence-bundle.schema-pack-promotion-test
  "Tests for pack-promotion-schema and evidence-bundle pack-promotions integration."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Test Helpers

(defn- make-test-promotion
  "Build a minimal valid pack-promotion map with the given justification string.
   All other fields use stable test values to isolate justification-specific tests."
  [justification]
  {:pack/id                 "pack-001"
   :pack/type               :knowledge
   :from-trust              :untrusted
   :to-trust                :trusted
   :promoted-by             "system"
   :promoted-at             (java.time.Instant/now)
   :promotion-policy        "knowledge-safety"
   :promotion-justification justification
   :pack-hash               "sha256:test"
   :pack-signature          ""})

;------------------------------------------------------------------------------ Layer 1
;; Pack Promotion Schema and Bundle Integration Tests

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

(deftest test-pack-promotion-schema-requires-justification
  (testing "Missing :promotion-justification fails validation"
    (let [promotion {:pack/id "test-pack-001"
                     :pack/type :knowledge
                     :from-trust :untrusted
                     :to-trust :trusted
                     :promoted-by "system"
                     :promoted-at (java.time.Instant/now)
                     :promotion-policy "knowledge-safety"
                     :pack-hash "sha256:abc123"}
          result (schema/validate-schema schema/pack-promotion-schema promotion)]
      (is (not (:valid? result))
          "Missing promotion-justification should fail validation")
      (is (some #(and (= :promotion-justification (:key %))
                      (= "Required key missing" (:error %)))
                (:errors result))
          "Should report promotion-justification as missing"))))

(deftest test-pack-promotion-justification-field
  (testing "Promotion without justification fails; with justification passes"
    (let [base             (dissoc (make-test-promotion "") :promotion-justification)
          result-without   (schema/validate-schema schema/pack-promotion-schema base)
          result-with      (schema/validate-schema schema/pack-promotion-schema
                                                   (assoc base :promotion-justification
                                                          "passed knowledge-safety scans"))]
      (is (not (:valid? result-without))
          "Promotion without justification should fail")
      (is (:valid? result-with)
          "Promotion with justification should pass")
      (is (empty? (:errors result-with))))))

(deftest test-pack-promotion-trust-levels
  (testing "Valid trust levels pass; invalid trust level fails"
    (let [valid-promotion   (make-test-promotion "passed scans")
          invalid-promotion (assoc valid-promotion :from-trust :invalid-level)
          result-valid      (schema/validate-schema schema/pack-promotion-schema valid-promotion)
          result-invalid    (schema/validate-schema schema/pack-promotion-schema invalid-promotion)]
      (is (:valid? result-valid)
          "Valid trust levels should pass")
      (is (not (:valid? result-invalid))
          "Invalid trust level should fail"))))

(deftest test-evidence-bundle-with-pack-promotions
  (testing "Evidence bundle accepts pack-promotions field"
    (let [bundle    (assoc (schema/create-evidence-bundle-template)
                           :evidence-bundle/workflow-id (random-uuid))
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
          bundle+   (assoc bundle :evidence/pack-promotions [promotion])
          result    (schema/validate-schema schema/evidence-bundle-schema bundle+)]
      (is (= 1 (count (:evidence/pack-promotions bundle+))))
      (is (= "manual review approved"
             (-> bundle+ :evidence/pack-promotions first :promotion-justification)))
      (is (:valid? result)
          (str "Bundle with pack promotions should pass evidence-bundle-schema; errors: "
               (:errors result))))))

(deftest test-evidence-bundle-template-includes-pack-promotions
  (testing "Evidence bundle template initializes pack-promotions as empty vector"
    (let [bundle (schema/create-evidence-bundle-template)]
      (is (contains? bundle :evidence/pack-promotions))
      (is (vector? (:evidence/pack-promotions bundle)))
      (is (empty? (:evidence/pack-promotions bundle))))))

(deftest test-justification-content-examples
  (testing "Common justification strings all pass schema validation"
    (let [justifications ["passed knowledge-safety scans with no violations"
                          "manual review approved by security team"
                          "verified signature from trusted key 0x123ABC"
                          "meets all policy compliance requirements"
                          "automated validation completed successfully"]
          results (map #(schema/validate-schema schema/pack-promotion-schema
                                                (make-test-promotion %))
                       justifications)]
      (is (every? :valid? results))
      (is (every? #(empty? (:errors %)) results)))))

(deftest test-schema-does-not-enforce-justification-content
  (testing "Schema accepts empty justification string; content enforcement is business-layer concern"
    (let [result (schema/validate-schema schema/pack-promotion-schema
                                         (make-test-promotion ""))]
      (is (:valid? result)
          "Schema accepts empty string — business logic is responsible for content checks"))))

(comment
  (clojure.test/run-tests)
  :leave-this-here)
