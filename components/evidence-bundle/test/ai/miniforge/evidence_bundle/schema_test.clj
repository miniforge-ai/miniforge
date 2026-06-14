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
  "Tests for evidence bundle schema validation.

   Covers the core validate-schema helper and all six compliance metadata
   schema additions specified by N6: enum membership, retention-policy-schema,
   access-log-entry-schema, evidence-bundle-schema with new optional fields,
   backwards compatibility, and create-evidence-bundle-template defaults."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Core Schema Validation

(deftest test-validate-schema-basic
  (testing "validate-schema accepts valid data"
    (let [valid-data {:constraint/type :pre
                      :constraint/description "Must exist"}
          result (schema/validate-schema schema/constraint-schema valid-data)]
      (is (:valid? result))
      (is (empty? (:errors result)))))
  (testing "validate-schema rejects missing required keys"
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
;; Compliance Metadata Schema

(deftest test-data-classifications-enum-contains-expected-members
  (testing "data-classifications contains all four N6-specified levels"
    (is (contains? schema/data-classifications :public))
    (is (contains? schema/data-classifications :internal))
    (is (contains? schema/data-classifications :confidential))
    (is (contains? schema/data-classifications :restricted)))
  (testing "data-classifications has no extra members beyond the four specified"
    (is (= 4 (count schema/data-classifications)))))

(deftest test-regulatory-tag-values-enum-contains-expected-members
  (testing "regulatory-tag-values contains all four N6-specified frameworks"
    (is (contains? schema/regulatory-tag-values :gdpr))
    (is (contains? schema/regulatory-tag-values :hipaa))
    (is (contains? schema/regulatory-tag-values :sox))
    (is (contains? schema/regulatory-tag-values :pci)))
  (testing "regulatory-tag-values has no extra members beyond the four specified"
    (is (= 4 (count schema/regulatory-tag-values)))))

(deftest test-retention-policy-schema-accepts-valid-map
  (testing "retention-policy-schema accepts a correctly shaped policy"
    (let [result (schema/validate-schema schema/retention-policy-schema
                                         {:retain-days 30 :auto-delete? true :legal-hold? false})]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest test-retention-policy-schema-rejects-missing-keys
  (testing "retention-policy-schema rejects a map missing :retain-days"
    (let [result (schema/validate-schema schema/retention-policy-schema
                                         {:auto-delete? true :legal-hold? false})]
      (is (not (:valid? result)))
      (is (some #(= :retain-days (:key %)) (:errors result)))))
  (testing "retention-policy-schema rejects a map missing :legal-hold?"
    (let [result (schema/validate-schema schema/retention-policy-schema
                                         {:retain-days 30 :auto-delete? true})]
      (is (not (:valid? result)))
      (is (some #(= :legal-hold? (:key %)) (:errors result)))))
  (testing "retention-policy-schema rejects a map missing :auto-delete?"
    (let [result (schema/validate-schema schema/retention-policy-schema
                                         {:retain-days 30 :legal-hold? false})]
      (is (not (:valid? result)))
      (is (some #(= :auto-delete? (:key %)) (:errors result))))))

(deftest test-access-log-entry-schema-accepts-valid-entry
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
  (testing "rejects entry missing :access-log/action"
    (let [result (schema/validate-schema schema/access-log-entry-schema
                                         {:access-log/principal "alice"
                                          :access-log/timestamp (java.time.Instant/now)})]
      (is (not (:valid? result)))
      (is (some #(= :access-log/action (:key %)) (:errors result)))))
  (testing "rejects entry missing :access-log/principal"
    (let [result (schema/validate-schema schema/access-log-entry-schema
                                         {:access-log/action    :read
                                          :access-log/timestamp (java.time.Instant/now)})]
      (is (not (:valid? result)))
      (is (some #(= :access-log/principal (:key %)) (:errors result)))))
  (testing "rejects entry missing :access-log/timestamp"
    (let [result (schema/validate-schema schema/access-log-entry-schema
                                         {:access-log/principal "alice"
                                          :access-log/action    :read})]
      (is (not (:valid? result)))
      (is (some #(= :access-log/timestamp (:key %)) (:errors result))))))

(deftest test-evidence-bundle-schema-accepts-bundle-with-new-optional-fields
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
               (:errors result)))))
  (testing "evidence-bundle-schema accepts every member of data-classifications"
    (doseq [cls schema/data-classifications]
      (let [bundle (-> (schema/create-evidence-bundle-template)
                       (assoc :evidence-bundle/workflow-id (random-uuid))
                       (assoc :evidence/data-classification cls))
            result (schema/validate-schema schema/evidence-bundle-schema bundle)]
        (is (:valid? result)
            (str "Classification " cls " should be accepted")))))
  (testing "evidence-bundle-schema rejects :evidence/data-classification outside the enum"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/data-classification :ultra-secret))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result)))
      (is (some #(= :evidence/data-classification (:key %)) (:errors result)))))
  (testing "evidence-bundle-schema rejects unknown regulatory tags"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/regulatory-tags #{:gdpr :unknown-framework}))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result)))
      (is (some #(= :evidence/regulatory-tags (:key %)) (:errors result))))))

(deftest test-evidence-bundle-schema-backwards-compatible
  (testing "Bundles without the new compliance fields still pass validation"
    (let [legacy {:evidence-bundle/id (random-uuid)
                  :evidence-bundle/workflow-id (random-uuid)
                  :evidence-bundle/created-at (java.time.Instant/now)
                  :evidence-bundle/version "1.0.0"
                  :evidence/intent {}
                  :evidence/policy-checks []
                  :evidence/outcome {}}
          result (schema/validate-schema schema/evidence-bundle-schema legacy)]
      (is (:valid? result)
          "Legacy bundle without compliance fields must pass schema validation")
      (is (empty? (:errors result))))))

(deftest test-evidence-bundle-schema-validates-nested-compliance-fields
  (testing "evidence-bundle-schema validates :evidence/retention-policy via retention-policy-schema"
    (let [bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/retention-policy {:retain-days -1
                                                        :auto-delete? true
                                                        :legal-hold? false}))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result)))))
  (testing "evidence-bundle-schema validates :evidence/access-log entries via access-log-entry-schema"
    (let [bad-entry {:access-log/principal "alice"
                     :access-log/timestamp (java.time.Instant/now)}
          bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/access-log [bad-entry]))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result)))))
  (testing "evidence-bundle-schema requires :evidence/access-log to be a vector"
    (let [bad-access-log {:access-log/principal "alice"
                          :access-log/action :read
                          :access-log/timestamp (java.time.Instant/now)}
          bundle (-> (schema/create-evidence-bundle-template)
                     (assoc :evidence-bundle/workflow-id (random-uuid))
                     (assoc :evidence/access-log bad-access-log))
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (not (:valid? result)))
      (is (some #(= :evidence/access-log (:key %)) (:errors result))))))

(deftest test-create-evidence-bundle-template-compliance-defaults
  (testing "Template emits all six compliance fields with correct defaults"
    (let [bundle (schema/create-evidence-bundle-template)]
      (is (= :internal (:evidence/data-classification bundle))
          ":evidence/data-classification defaults to :internal")
      (is (false? (:evidence/contains-pii? bundle))
          ":evidence/contains-pii? defaults to false")
      (is (map? (:evidence/retention-policy bundle))
          ":evidence/retention-policy defaults to a map")
      (is (contains? (:evidence/retention-policy bundle) :retain-days))
      (is (contains? (:evidence/retention-policy bundle) :auto-delete?))
      (is (contains? (:evidence/retention-policy bundle) :legal-hold?))
      (is (true? (get-in bundle [:evidence/retention-policy :auto-delete?]))
          ":auto-delete? defaults to true")
      (is (false? (get-in bundle [:evidence/retention-policy :legal-hold?]))
          ":legal-hold? defaults to false")
      (is (= schema/default-retention-days
             (get-in bundle [:evidence/retention-policy :retain-days]))
          ":retain-days equals the named schema constant")
      (is (= #{} (:evidence/regulatory-tags bundle))
          ":evidence/regulatory-tags defaults to empty set")
      (is (= schema/default-created-by-principal (:evidence/created-by bundle))
          ":evidence/created-by defaults to system principal")
      (is (= [] (:evidence/access-log bundle))
          ":evidence/access-log defaults to empty vector"))))

(comment
  (clojure.test/run-tests)
  :leave-this-here)
