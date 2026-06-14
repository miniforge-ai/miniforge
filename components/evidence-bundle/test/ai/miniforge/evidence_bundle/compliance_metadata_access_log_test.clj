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

(ns ai.miniforge.evidence-bundle.compliance-metadata-access-log-test
  "Tests for append-access-log-entry and validate-schema round-trips.

   Scope:
   - append-access-log-entry appends entries correctly
   - append-access-log-entry preserves existing entries (append-only)
   - append-access-log-entry stamps :access-log/timestamp when absent
   - Bundles produced by assemble-evidence-bundle pass validate-schema"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.collector :as collector]
   [ai.miniforge.evidence-bundle.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures and Named Constants

(def ^:private workflow-id
  #uuid "cafebabe-0000-0000-0000-000000000003")

(def ^:private base-workflow-state
  {:workflow/status :completed
   :workflow/spec   {:intent/type :update
                     :description "access-log test workflow"}
   :workflow/phases {}})

(def ^:private two-year-retention-days
  "Two-year retention used in the validate-schema round-trip test to exercise
   a non-default :retain-days value end-to-end through assemble-evidence-bundle."
  730)

;------------------------------------------------------------------------------ Layer 1
;; append-access-log-entry

(deftest append-access-log-entry-appends-to-empty-log
  (testing "entry is conj'd onto an empty :evidence/access-log"
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

(deftest append-access-log-entry-appends-after-existing-entries
  (testing "new entry lands after all pre-existing entries"
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

(deftest append-access-log-entry-preserves-all-existing-entries
  (testing "append-only contract: existing entries are never removed or mutated"
    (let [t1     (java.time.Instant/parse "2024-01-01T00:00:00Z")
          t2     (java.time.Instant/parse "2024-06-01T00:00:00Z")
          e1     {:access-log/principal "alice" :access-log/action :read  :access-log/timestamp t1}
          e2     {:access-log/principal "bob"   :access-log/action :audit :access-log/timestamp t2}
          bundle (reduce collector/append-access-log-entry {:evidence/access-log []} [e1 e2])
          e3     {:access-log/principal "carol" :access-log/action :export :access-log/timestamp (java.time.Instant/now)}
          result (collector/append-access-log-entry bundle e3)
          log    (:evidence/access-log result)]
      (is (= 3 (count log)))
      (is (= "alice" (:access-log/principal (nth log 0))))
      (is (= t1      (:access-log/timestamp (nth log 0))))
      (is (= "bob"   (:access-log/principal (nth log 1))))
      (is (= t2      (:access-log/timestamp (nth log 1))))
      (is (= "carol" (:access-log/principal (nth log 2)))))))

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
  (testing "fnil guard initializes :evidence/access-log when key is absent from bundle"
    (let [result (collector/append-access-log-entry
                  {}
                  {:access-log/principal "init-test"
                   :access-log/action    :read
                   :access-log/timestamp (java.time.Instant/now)})]
      (is (vector? (:evidence/access-log result)))
      (is (= 1 (count (:evidence/access-log result)))))))

;------------------------------------------------------------------------------ Layer 2
;; validate-schema Round-Trip

(deftest assemble-bundle-passes-validate-schema-with-defaults
  (testing "default assembled bundle satisfies evidence-bundle-schema"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil)
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (:valid? result)
          (str "Default assembled bundle must pass evidence-bundle-schema; errors: "
               (:errors result))))))

(deftest assemble-bundle-passes-validate-schema-with-overrides
  (testing "assembled bundle with full compliance overrides satisfies evidence-bundle-schema"
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
                                {:retain-days  two-year-retention-days
                                 :auto-delete? false
                                 :legal-hold?  true}}})
          result (schema/validate-schema schema/evidence-bundle-schema bundle)]
      (is (:valid? result)
          (str "Fully-overridden bundle must pass evidence-bundle-schema; errors: "
               (:errors result))))))

(deftest assemble-bundle-passes-validate-schema-with-access-log-entry
  (testing "bundle with an access-log entry appended satisfies evidence-bundle-schema"
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

(comment
  (clojure.test/run-tests)
  :leave-this-here)
