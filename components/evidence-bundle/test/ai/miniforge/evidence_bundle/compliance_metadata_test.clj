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

   Covers via the public interface:
   1. assemble-evidence-bundle produces default compliance metadata
   2. assemble-evidence-bundle applies workflow-spec :compliance overrides
   3. assemble-evidence-bundle applies opts :compliance overrides
   4. Partial :evidence/retention-policy override merges with defaults
   5. append-access-log-entry appends entries correctly
   6. append-access-log-entry preserves existing entries (append-only)
   7. append-access-log-entry stamps timestamp when missing
   8. Bundle produced by assemble-evidence-bundle passes validate-schema"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.collector :as collector]
   [ai.miniforge.evidence-bundle.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(def ^:private workflow-id
  #uuid "cafebabe-0000-0000-0000-000000000001")

(def ^:private base-workflow-state
  {:workflow/status :completed
   :workflow/spec   {:intent/type :update
                     :description "compliance-metadata test workflow"}
   :workflow/phases {}})

;------------------------------------------------------------------------------ Layer 1
;; Default compliance metadata

(deftest assemble-produces-default-data-classification
  (testing "assembled bundle carries :internal data-classification by default"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil)]
      (is (= schema/default-data-classification
             (:evidence/data-classification bundle))
          ":evidence/data-classification must default to :internal"))))

(deftest assemble-produces-default-contains-pii-false
  (testing "assembled bundle has :evidence/contains-pii? false by default"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil)]
      (is (false? (:evidence/contains-pii? bundle))))))

(deftest assemble-produces-default-retention-policy
  (testing "assembled bundle has retention policy with default-retention-days"
    (let [bundle     (collector/assemble-evidence-bundle
                      workflow-id base-workflow-state nil)
          retention  (:evidence/retention-policy bundle)]
      (is (map? retention)
          ":evidence/retention-policy must be a map")
      (is (= schema/default-retention-days (:retain-days retention))
          ":retain-days must equal schema/default-retention-days")
      (is (true? (:auto-delete? retention))
          ":auto-delete? must default to true")
      (is (false? (:legal-hold? retention))
          ":legal-hold? must default to false"))))

(deftest assemble-produces-default-regulatory-tags
  (testing "assembled bundle has empty :evidence/regulatory-tags by default"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil)]
      (is (= #{} (:evidence/regulatory-tags bundle))
          ":evidence/regulatory-tags must default to empty set"))))

(deftest assemble-produces-default-created-by
  (testing "assembled bundle has schema/default-created-by-principal by default"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil)]
      (is (= schema/default-created-by-principal
             (:evidence/created-by bundle))
          ":evidence/created-by must default to the named system principal"))))

(deftest assemble-produces-default-access-log
  (testing "assembled bundle has empty :evidence/access-log by default"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil)]
      (is (vector? (:evidence/access-log bundle)))
      (is (empty? (:evidence/access-log bundle))))))

;------------------------------------------------------------------------------ Layer 2
;; Overrides from workflow-spec :compliance

(deftest assemble-applies-spec-data-classification-override
  (testing "workflow-spec :compliance can set :evidence/data-classification"
    (let [state  (assoc base-workflow-state
                        :workflow/spec
                        {:intent/type :update
                         :description "classified run"
                         :compliance  {:evidence/data-classification :restricted}})
          bundle (collector/assemble-evidence-bundle workflow-id state nil)]
      (is (= :restricted (:evidence/data-classification bundle))
          "Spec-level classification override must appear on the bundle"))))

(deftest assemble-applies-spec-contains-pii-override
  (testing "workflow-spec :compliance can flag :evidence/contains-pii? true"
    (let [state  (assoc base-workflow-state
                        :workflow/spec
                        {:intent/type :update
                         :description "pii workflow"
                         :compliance  {:evidence/contains-pii? true}})
          bundle (collector/assemble-evidence-bundle workflow-id state nil)]
      (is (true? (:evidence/contains-pii? bundle))))))

(deftest assemble-applies-spec-regulatory-tags-override
  (testing "workflow-spec :compliance can set :evidence/regulatory-tags"
    (let [state  (assoc base-workflow-state
                        :workflow/spec
                        {:intent/type :update
                         :description "gdpr run"
                         :compliance  {:evidence/regulatory-tags #{:gdpr :sox}}})
          bundle (collector/assemble-evidence-bundle workflow-id state nil)]
      (is (= #{:gdpr :sox} (:evidence/regulatory-tags bundle))))))

;------------------------------------------------------------------------------ Layer 3
;; Overrides from opts :compliance

(deftest assemble-applies-opts-data-classification-override
  (testing "opts :compliance can override :evidence/data-classification"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/data-classification :confidential}})]
      (is (= :confidential (:evidence/data-classification bundle))
          "opts-level classification override must appear on the bundle"))))

(deftest assemble-applies-opts-contains-pii-override
  (testing "opts :compliance can set :evidence/contains-pii? true"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/contains-pii? true}})]
      (is (true? (:evidence/contains-pii? bundle))))))

(deftest assemble-applies-opts-created-by-override
  (testing "opts :compliance can override :evidence/created-by"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/created-by "operator-alice"}})]
      (is (= "operator-alice" (:evidence/created-by bundle))))))

;------------------------------------------------------------------------------ Layer 4
;; Partial :evidence/retention-policy merges with defaults

(deftest assemble-partial-retention-policy-merges-legal-hold
  (testing "partial :evidence/retention-policy — only :legal-hold? true merges with defaults"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy {:legal-hold? true}}})]
      (is (true? (get-in bundle [:evidence/retention-policy :legal-hold?]))
          ":legal-hold? override must take effect")
      ;; Other keys from the defaults must survive the partial merge
      (is (= schema/default-retention-days
             (get-in bundle [:evidence/retention-policy :retain-days]))
          ":retain-days must survive a partial override")
      (is (true? (get-in bundle [:evidence/retention-policy :auto-delete?]))
          ":auto-delete? must survive a partial override"))))

(deftest assemble-partial-retention-policy-merges-retain-days
  (testing "partial :evidence/retention-policy — only :retain-days 365 merges with defaults"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy {:retain-days 365}}})]
      (is (= 365 (get-in bundle [:evidence/retention-policy :retain-days])))
      (is (contains? (:evidence/retention-policy bundle) :auto-delete?))
      (is (contains? (:evidence/retention-policy bundle) :legal-hold?)))))

(deftest assemble-full-retention-policy-override
  (testing "full :evidence/retention-policy override replaces all three sub-keys"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy
                                {:retain-days  2555
                                 :auto-delete? false
                                 :legal-hold?  true}}})]
      (is (= 2555 (get-in bundle [:evidence/retention-policy :retain-days])))
      (is (false? (get-in bundle [:evidence/retention-policy :auto-delete?])))
      (is (true?  (get-in bundle [:evidence/retention-policy :legal-hold?]))))))

;------------------------------------------------------------------------------ Layer 5
;; append-access-log-entry

(deftest append-access-log-entry-appends-to-empty-log
  (testing "append-access-log-entry adds an entry to an empty access log"
    (let [bundle {:evidence/access-log []}
          entry  {:access-log/principal "alice@example.com"
                  :access-log/action    :read
                  :access-log/timestamp (java.time.Instant/now)}
          result (collector/append-access-log-entry bundle entry)]
      (is (= 1 (count (:evidence/access-log result))))
      (is (= "alice@example.com"
             (:access-log/principal (first (:evidence/access-log result)))))
      (is (= :read
             (:access-log/action (first (:evidence/access-log result))))))))

(deftest append-access-log-entry-appends-to-non-empty-log
  (testing "append-access-log-entry appends after existing entries"
    (let [existing {:access-log/principal "bob"
                    :access-log/action    :export
                    :access-log/timestamp (java.time.Instant/now)}
          bundle   {:evidence/access-log [existing]}
          entry    {:access-log/principal "alice"
                    :access-log/action    :read
                    :access-log/timestamp (java.time.Instant/now)}
          result   (collector/append-access-log-entry bundle entry)]
      (is (= 2 (count (:evidence/access-log result)))
          "Both entries must be present after append")
      (is (= "bob"   (:access-log/principal (first  (:evidence/access-log result)))))
      (is (= "alice" (:access-log/principal (second (:evidence/access-log result))))))))

;------------------------------------------------------------------------------ Layer 6
;; append-access-log-entry preserves existing entries (append-only)

(deftest append-access-log-entry-preserves-all-existing-entries
  (testing "append is idempotent w.r.t. existing entries — none are removed or mutated"
    (let [t1   (java.time.Instant/parse "2024-01-01T00:00:00Z")
          t2   (java.time.Instant/parse "2024-06-01T00:00:00Z")
          e1   {:access-log/principal "alice" :access-log/action :read  :access-log/timestamp t1}
          e2   {:access-log/principal "bob"   :access-log/action :audit :access-log/timestamp t2}
          bundle (reduce collector/append-access-log-entry
                         {:evidence/access-log []}
                         [e1 e2])
          ;; Append a third entry
          e3   {:access-log/principal "carol" :access-log/action :export :access-log/timestamp (java.time.Instant/now)}
          result (collector/append-access-log-entry bundle e3)
          log    (:evidence/access-log result)]
      (is (= 3 (count log)))
      ;; e1 and e2 untouched
      (is (= "alice" (:access-log/principal (nth log 0))))
      (is (= t1      (:access-log/timestamp (nth log 0))))
      (is (= "bob"   (:access-log/principal (nth log 1))))
      (is (= t2      (:access-log/timestamp (nth log 1))))
      (is (= "carol" (:access-log/principal (nth log 2)))))))

;------------------------------------------------------------------------------ Layer 7
;; append-access-log-entry stamps timestamp when missing

(deftest append-access-log-entry-stamps-timestamp-when-absent
  (testing ":access-log/timestamp is auto-added when not provided"
    (let [bundle {:evidence/access-log []}
          entry  {:access-log/principal "service-account"
                  :access-log/action    :validate}
          result (collector/append-access-log-entry bundle entry)
          logged (first (:evidence/access-log result))]
      (is (contains? logged :access-log/timestamp)
          "Stamped entry must carry :access-log/timestamp")
      (is (instance? java.time.Instant (:access-log/timestamp logged))
          ":access-log/timestamp must be a java.time.Instant"))))

(deftest append-access-log-entry-preserves-existing-timestamp
  (testing ":access-log/timestamp is NOT overwritten when already present"
    (let [fixed-ts (java.time.Instant/parse "2024-03-15T12:00:00Z")
          bundle   {:evidence/access-log []}
          entry    {:access-log/principal "auditor"
                    :access-log/action    :read
                    :access-log/timestamp fixed-ts}
          result   (collector/append-access-log-entry bundle entry)
          logged   (first (:evidence/access-log result))]
      (is (= fixed-ts (:access-log/timestamp logged))
          "Pre-existing timestamp must be preserved verbatim"))))

(deftest append-access-log-entry-initializes-missing-access-log
  (testing "fnil guard initializes :evidence/access-log when key is absent"
    (let [result (collector/append-access-log-entry
                  {}
                  {:access-log/principal "init-test"
                   :access-log/action    :read
                   :access-log/timestamp (java.time.Instant/now)})]
      (is (vector? (:evidence/access-log result)))
      (is (= 1 (count (:evidence/access-log result)))))))

;------------------------------------------------------------------------------ Layer 8
;; Bundle produced by assemble-evidence-bundle passes validate-schema

(deftest assemble-bundle-passes-validate-schema-with-defaults
  (testing "default assembled bundle satisfies evidence-bundle-schema"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil)
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (:valid? result)
          (str "Default assembled bundle must pass evidence-bundle-schema; errors: "
               (:errors result))))))

(deftest assemble-bundle-passes-validate-schema-with-overrides
  (testing "assembled bundle with all compliance overrides satisfies evidence-bundle-schema"
    (let [state  (assoc base-workflow-state
                        :workflow/spec
                        {:intent/type :update
                         :description "fully-classified run"
                         :compliance  {:evidence/data-classification :confidential
                                       :evidence/regulatory-tags     #{:gdpr}}})
          bundle (collector/assemble-evidence-bundle
                  workflow-id state nil
                  {:compliance {:evidence/contains-pii? true
                                :evidence/created-by    "operator-charlie"
                                :evidence/retention-policy
                                {:retain-days  730
                                 :auto-delete? false
                                 :legal-hold?  true}}})
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (:valid? result)
          (str "Fully-overridden bundle must pass evidence-bundle-schema; errors: "
               (:errors result))))))

(deftest assemble-bundle-passes-validate-schema-with-access-log-entry
  (testing "bundle with an access log entry appended satisfies evidence-bundle-schema"
    (let [bundle  (collector/assemble-evidence-bundle
                   workflow-id base-workflow-state nil)
          bundle+ (collector/append-access-log-entry
                   bundle
                   {:access-log/principal "auditor@example.com"
                    :access-log/action    :read})
          result  (schema/validate-schema schema/evidence-bundle-schema bundle+)]
      (is (:valid? result)
          (str "Bundle with appended access-log entry must pass evidence-bundle-schema; errors: "
               (:errors result))))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Run all tests in this namespace
  (clojure.test/run-tests)

  ;; Quick smoke-test: assembled bundle defaults
  (let [wf-id  #uuid "cafebabe-0000-0000-0000-000000000099"
        state  {:workflow/status :completed
                :workflow/spec   {:intent/type :update :description "smoke"}
                :workflow/phases {}}
        bundle (collector/assemble-evidence-bundle wf-id state nil)]
    (select-keys bundle [:evidence/data-classification
                         :evidence/contains-pii?
                         :evidence/retention-policy
                         :evidence/regulatory-tags
                         :evidence/created-by
                         :evidence/access-log]))

  :end)
