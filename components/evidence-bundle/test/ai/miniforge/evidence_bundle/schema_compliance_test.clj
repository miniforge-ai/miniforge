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

(ns ai.miniforge.evidence-bundle.schema-compliance-test
  "Tests for the compliance metadata additions to the evidence bundle schema.

   Covers:
   - create-evidence-bundle-template includes all six compliance defaults
   - evidence-bundle-schema backwards-compatibility (no new fields required)
   - data-classifications and regulatory-tag-values enum membership
   - retention-policy-schema direct validation
   - access-log-entry-schema direct validation
   - evidence-bundle-schema accepts bundles with new optional compliance fields"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Template Defaults and Backwards Compatibility

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
      (testing ":evidence/retention-policy :auto-delete? is true by default"
        (is (true? (get-in bundle [:evidence/retention-policy :auto-delete?]))))
      (testing ":evidence/retention-policy :legal-hold? is false by default"
        (is (false? (get-in bundle [:evidence/retention-policy :legal-hold?]))))
      (testing ":evidence/regulatory-tags defaults to empty set"
        (is (= #{} (:evidence/regulatory-tags bundle))))
      (testing ":evidence/created-by defaults to system principal"
        (is (= schema/default-created-by-principal (:evidence/created-by bundle))))
      (testing ":evidence/access-log defaults to empty vector"
        (is (= [] (:evidence/access-log bundle)))))))

(deftest test-template-retain-days-from-named-constant
  (testing ":retain-days in template equals the named constant, not a different value"
    (let [retain-days (get-in (schema/create-evidence-bundle-template)
                              [:evidence/retention-policy :retain-days])]
      (is (= schema/default-retention-days retain-days)))))

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

;------------------------------------------------------------------------------ Layer 1
;; Enum Membership and Sub-Schema Validation

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

(deftest test-retention-policy-schema-valid
  (testing "retention-policy-schema accepts a correctly shaped policy"
    (let [valid-policy {:retain-days 30 :auto-delete? true :legal-hold? false}
          result       (schema/validate-schema schema/retention-policy-schema valid-policy)]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest test-retention-policy-schema-rejects-missing-keys
  (testing "retention-policy-schema rejects maps missing required keys"
    (let [missing-legal-hold {:retain-days 30 :auto-delete? true}
          result (schema/validate-schema schema/retention-policy-schema missing-legal-hold)]
      (is (not (:valid? result)))
      (is (some #(= :legal-hold? (:key %)) (:errors result))))
    (let [missing-auto-delete {:retain-days 30 :legal-hold? false}
          result (schema/validate-schema schema/retention-policy-schema missing-auto-delete)]
      (is (not (:valid? result)))
      (is (some #(= :auto-delete? (:key %)) (:errors result))))))

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

(deftest test-access-log-entry-schema-rejects-missing-fields
  (testing "access-log-entry-schema rejects entry missing :access-log/action"
    (let [entry  {:access-log/principal "alice@example.com"
                  :access-log/timestamp (java.time.Instant/now)}
          result (schema/validate-schema schema/access-log-entry-schema entry)]
      (is (not (:valid? result)))
      (is (some #(= :access-log/action (:key %)) (:errors result)))))
  (testing "access-log-entry-schema rejects entry missing :access-log/principal"
    (let [entry  {:access-log/action    :read
                  :access-log/timestamp (java.time.Instant/now)}
          result (schema/validate-schema schema/access-log-entry-schema entry)]
      (is (not (:valid? result)))
      (is (some #(= :access-log/principal (:key %)) (:errors result)))))
  (testing "access-log-entry-schema rejects entry missing :access-log/timestamp"
    (let [entry  {:access-log/principal "alice@example.com"
                  :access-log/action    :read}
          result (schema/validate-schema schema/access-log-entry-schema entry)]
      (is (not (:valid? result)))
      (is (some #(= :access-log/timestamp (:key %)) (:errors result))))))

;------------------------------------------------------------------------------ Layer 2
;; evidence-bundle-schema Accepts Bundles With New Optional Fields

(deftest test-evidence-bundle-schema-accepts-bundle-with-all-new-fields
  (testing "evidence-bundle-schema accepts a bundle carrying all six new compliance fields"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/data-classification :confidential)
                     (assoc :evidence/contains-pii? true)
                     (assoc :evidence/retention-policy {:retain-days  30
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

(deftest test-evidence-bundle-schema-wires-retention-policy
  (testing "evidence-bundle-schema validates :evidence/retention-policy via retention-policy-schema"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/retention-policy {:retain-days -1
                                                        :auto-delete? true
                                                        :legal-hold? false}))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result))
          "Bundle with invalid retention policy should fail validation"))))

(deftest test-evidence-bundle-schema-wires-access-log
  (testing "evidence-bundle-schema validates access-log entries via access-log-entry-schema"
    (let [bad-entry {:access-log/principal "alice"
                     :access-log/timestamp (java.time.Instant/now)}
          ;; missing :access-log/action
          bundle    (-> (schema/create-evidence-bundle-template)
                        (assoc :evidence-bundle/workflow-id (random-uuid))
                        (assoc :evidence/access-log [bad-entry]))
          result    (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result))
          "Bundle with invalid access-log entry should fail validation"))))

(comment
  (clojure.test/run-tests)
  :leave-this-here)
