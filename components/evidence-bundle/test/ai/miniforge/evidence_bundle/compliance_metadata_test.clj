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

(ns ai.miniforge.evidence-bundle.compliance-metadata-test
  "Tests for compliance metadata assembly, defaults, overrides, and access-log.

   Covers:
   1. assemble-evidence-bundle produces bundles with default compliance metadata
   2. assemble-evidence-bundle applies overrides from workflow-spec :compliance
   3. assemble-evidence-bundle applies overrides from opts :compliance
   4. Partial :evidence/retention-policy override merges with defaults
   5. append-access-log-entry appends entries correctly
   6. append-access-log-entry preserves existing entries (append-only)
   7. append-access-log-entry adds timestamp when missing
   8. Bundle produced by assemble-evidence-bundle passes validate-schema"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.collector :as collector]
   [ai.miniforge.evidence-bundle.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures and Named Constants

(def ^:private workflow-id
  #uuid "cafebabe-0000-0000-0000-000000000001")

(def ^:private base-workflow-state
  {:workflow/status :completed
   :workflow/spec   {:intent/type :update
                     :description "compliance-metadata test workflow"}
   :workflow/phases {}})

(def ^:private one-year-retention-days
  "Retention period used in override tests to verify a non-default value
   is accepted and survives the round-trip through assemble-evidence-bundle."
  365)

(def ^:private two-year-retention-days
  "Two-year retention used in the validate-schema round-trip test to exercise
   a non-default :retain-days value end-to-end through assemble-evidence-bundle."
  730)

(def ^:private seven-year-retention-days
  "Seven-year retention (2555 days) used to test full override of all three
   retention-policy sub-keys simultaneously. SOX mandates 7 years."
  2555)

;------------------------------------------------------------------------------ Layer 1
;; assemble-evidence-bundle: Defaults and Overrides

(deftest assemble-produces-default-compliance-metadata
  (testing "assembled bundle carries default data-classification (:internal)"
    (let [bundle (collector/assemble-evidence-bundle workflow-id base-workflow-state nil)]
      (is (= schema/default-data-classification
             (:evidence/data-classification bundle)))))
  (testing "assembled bundle has :evidence/contains-pii? false by default"
    (let [bundle (collector/assemble-evidence-bundle workflow-id base-workflow-state nil)]
      (is (false? (:evidence/contains-pii? bundle)))))
  (testing "assembled bundle has retention policy with schema-defined defaults"
    (let [retention (:evidence/retention-policy
                     (collector/assemble-evidence-bundle workflow-id base-workflow-state nil))]
      (is (map? retention))
      (is (= schema/default-retention-days (:retain-days retention)))
      (is (true? (:auto-delete? retention)))
      (is (false? (:legal-hold? retention)))))
  (testing "assembled bundle has empty :evidence/regulatory-tags by default"
    (let [bundle (collector/assemble-evidence-bundle workflow-id base-workflow-state nil)]
      (is (= #{} (:evidence/regulatory-tags bundle)))))
  (testing "assembled bundle has schema/default-created-by-principal"
    (let [bundle (collector/assemble-evidence-bundle workflow-id base-workflow-state nil)]
      (is (= schema/default-created-by-principal (:evidence/created-by bundle)))))
  (testing "assembled bundle has empty :evidence/access-log by default"
    (let [bundle (collector/assemble-evidence-bundle workflow-id base-workflow-state nil)]
      (is (vector? (:evidence/access-log bundle)))
      (is (empty? (:evidence/access-log bundle))))))

(deftest assemble-applies-workflow-spec-compliance-overrides
  (testing "workflow-spec :compliance overrides :evidence/data-classification"
    (let [state  (assoc base-workflow-state :workflow/spec
                        {:intent/type :update :description "classified"
                         :compliance  {:evidence/data-classification :restricted}})
          bundle (collector/assemble-evidence-bundle workflow-id state nil)]
      (is (= :restricted (:evidence/data-classification bundle)))))
  (testing "workflow-spec :compliance overrides :evidence/contains-pii?"
    (let [state  (assoc base-workflow-state :workflow/spec
                        {:intent/type :update :description "pii"
                         :compliance  {:evidence/contains-pii? true}})
          bundle (collector/assemble-evidence-bundle workflow-id state nil)]
      (is (true? (:evidence/contains-pii? bundle)))))
  (testing "workflow-spec :compliance overrides :evidence/regulatory-tags"
    (let [state  (assoc base-workflow-state :workflow/spec
                        {:intent/type :update :description "gdpr"
                         :compliance  {:evidence/regulatory-tags #{:gdpr :sox}}})
          bundle (collector/assemble-evidence-bundle workflow-id state nil)]
      (is (= #{:gdpr :sox} (:evidence/regulatory-tags bundle))))))

(deftest assemble-applies-opts-compliance-overrides
  (testing "opts :compliance overrides :evidence/data-classification"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/data-classification :confidential}})]
      (is (= :confidential (:evidence/data-classification bundle)))))
  (testing "opts :compliance overrides :evidence/contains-pii?"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/contains-pii? true}})]
      (is (true? (:evidence/contains-pii? bundle)))))
  (testing "opts :compliance overrides :evidence/created-by"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/created-by "operator-alice"}})]
      (is (= "operator-alice" (:evidence/created-by bundle))))))

(deftest assemble-partial-retention-policy-merges-with-defaults
  (testing "only :legal-hold? true — :retain-days and :auto-delete? survive from defaults"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy {:legal-hold? true}}})]
      (is (true?  (get-in bundle [:evidence/retention-policy :legal-hold?])))
      (is (= schema/default-retention-days
             (get-in bundle [:evidence/retention-policy :retain-days])))
      (is (true? (get-in bundle [:evidence/retention-policy :auto-delete?])))))
  (testing "only :retain-days supplied — :auto-delete? and :legal-hold? survive from defaults"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy
                                {:retain-days one-year-retention-days}}})]
      (is (= one-year-retention-days (get-in bundle [:evidence/retention-policy :retain-days])))
      (is (true? (get-in bundle [:evidence/retention-policy :auto-delete?])))
      (is (false? (get-in bundle [:evidence/retention-policy :legal-hold?])))))
  (testing "full override replaces all three sub-keys"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy
                                {:retain-days  seven-year-retention-days
                                 :auto-delete? false
                                 :legal-hold?  true}}})]
      (is (= seven-year-retention-days (get-in bundle [:evidence/retention-policy :retain-days])))
      (is (false? (get-in bundle [:evidence/retention-policy :auto-delete?])))
      (is (true?  (get-in bundle [:evidence/retention-policy :legal-hold?]))))))

;------------------------------------------------------------------------------ Layer 2
;; append-access-log-entry and validate-schema Round-Trips

(deftest append-access-log-entry-appends-entries-correctly
  (testing "entry is appended to an empty access log"
    (let [result (collector/append-access-log-entry
                  {:evidence/access-log []}
                  {:access-log/principal "alice@example.com"
                   :access-log/action    :read
                   :access-log/timestamp (java.time.Instant/now)})]
      (is (= 1 (count (:evidence/access-log result))))
      (is (= "alice@example.com"
             (:access-log/principal (first (:evidence/access-log result)))))))
  (testing "entry lands after pre-existing entries"
    (let [existing {:access-log/principal "bob"
                    :access-log/action    :export
                    :access-log/timestamp (java.time.Instant/now)}
          result   (collector/append-access-log-entry
                    {:evidence/access-log [existing]}
                    {:access-log/principal "alice"
                     :access-log/action    :read
                     :access-log/timestamp (java.time.Instant/now)})]
      (is (= 2 (count (:evidence/access-log result))))
      (is (= "bob"   (:access-log/principal (first  (:evidence/access-log result)))))
      (is (= "alice" (:access-log/principal (second (:evidence/access-log result))))))))

(deftest append-access-log-entry-preserves-existing-entries
  (testing "append-only: existing entries are never removed or mutated"
    (let [t1     (java.time.Instant/parse "2024-01-01T00:00:00Z")
          t2     (java.time.Instant/parse "2024-06-01T00:00:00Z")
          e1     {:access-log/principal "alice" :access-log/action :read  :access-log/timestamp t1}
          e2     {:access-log/principal "bob"   :access-log/action :audit :access-log/timestamp t2}
          bundle (reduce collector/append-access-log-entry {:evidence/access-log []} [e1 e2])
          result (collector/append-access-log-entry
                  bundle
                  {:access-log/principal "carol"
                   :access-log/action    :export
                   :access-log/timestamp (java.time.Instant/now)})
          log    (:evidence/access-log result)]
      (is (= 3 (count log)))
      (is (= "alice" (:access-log/principal (nth log 0))))
      (is (= t1      (:access-log/timestamp (nth log 0))))
      (is (= "bob"   (:access-log/principal (nth log 1))))
      (is (= t2      (:access-log/timestamp (nth log 1))))
      (is (= "carol" (:access-log/principal (nth log 2)))))))

(deftest append-access-log-entry-stamps-timestamp-when-absent
  (testing ":access-log/timestamp is auto-added when missing"
    (let [result (collector/append-access-log-entry
                  {:evidence/access-log []}
                  {:access-log/principal "service-account"
                   :access-log/action    :validate})
          logged (first (:evidence/access-log result))]
      (is (contains? logged :access-log/timestamp))
      (is (instance? java.time.Instant (:access-log/timestamp logged)))))
  (testing ":access-log/timestamp is NOT overwritten when already present"
    (let [fixed-ts (java.time.Instant/parse "2024-03-15T12:00:00Z")
          result   (collector/append-access-log-entry
                    {:evidence/access-log []}
                    {:access-log/principal "auditor"
                     :access-log/action    :read
                     :access-log/timestamp fixed-ts})
          logged   (first (:evidence/access-log result))]
      (is (= fixed-ts (:access-log/timestamp logged))))))

(deftest assembled-bundle-passes-validate-schema
  (testing "default assembled bundle satisfies evidence-bundle-schema"
    (let [result (schema/validate-schema schema/evidence-bundle-schema
                                         (collector/assemble-evidence-bundle
                                          workflow-id base-workflow-state nil))]
      (is (:valid? result)
          (str "Default bundle must pass evidence-bundle-schema; errors: "
               (:errors result)))))
  (testing "bundle with full compliance overrides satisfies evidence-bundle-schema"
    (let [state  (assoc base-workflow-state :workflow/spec
                        {:intent/type :update :description "fully-classified"
                         :compliance  {:evidence/data-classification :confidential
                                       :evidence/regulatory-tags     #{:gdpr}}})
          bundle (collector/assemble-evidence-bundle
                  workflow-id state nil
                  {:compliance {:evidence/contains-pii? true
                                :evidence/created-by    "operator-charlie"
                                :evidence/retention-policy
                                {:retain-days  two-year-retention-days
                                 :auto-delete? false
                                 :legal-hold?  true}}})
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (:valid? result)
          (str "Overridden bundle must pass evidence-bundle-schema; errors: "
               (:errors result)))))
  (testing "bundle with appended access-log entry satisfies evidence-bundle-schema"
    (let [bundle  (collector/assemble-evidence-bundle workflow-id base-workflow-state nil)
          bundle+ (collector/append-access-log-entry
                   bundle
                   {:access-log/principal "auditor@example.com"
                    :access-log/action    :read})
          result  (schema/validate-schema schema/evidence-bundle-schema bundle+)]
      (is (:valid? result)
          (str "Bundle with access-log entry must pass evidence-bundle-schema; errors: "
               (:errors result))))))

(comment
  (clojure.test/run-tests)
  :leave-this-here)
