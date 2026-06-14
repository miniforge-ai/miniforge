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

(ns ai.miniforge.evidence-bundle.collector-compliance-test
  "Unit tests for append-access-log-entry and the compliance-override path
   in assemble-evidence-bundle.

   Covers:
   - append-access-log-entry standalone contract
   - build-default-compliance-metadata via assembled bundle
   - extract-compliance-overrides via assembled bundle (spec-level, opts-level)
   - merge-compliance partial :evidence/retention-policy override"
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.evidence-bundle.collector :as collector]
   [ai.miniforge.evidence-bundle.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(def ^:private base-workflow-state
  {:workflow/status :completed
   :workflow/spec   {:intent/type :update
                     :description "test workflow"}
   :workflow/phases {}})

(def ^:private workflow-id
  #uuid "00000000-0000-0000-0000-000000000042")

;------------------------------------------------------------------------------ Layer 1
;; append-access-log-entry

(deftest append-access-log-entry-appends-entry
  (testing "entry is conj'd onto :evidence/access-log"
    (let [bundle {:evidence/access-log []}
          entry  {:access-log/principal "alice"
                  :access-log/action    :read
                  :access-log/timestamp (java.time.Instant/now)}
          result (collector/append-access-log-entry bundle entry)]
      (is (= 1 (count (:evidence/access-log result))))
      (is (= "alice" (:access-log/principal (first (:evidence/access-log result))))))))

(deftest append-access-log-entry-preserves-existing
  (testing "prior entries remain after append"
    (let [prior  {:access-log/principal "bob"
                  :access-log/action    :export
                  :access-log/timestamp (java.time.Instant/now)}
          bundle {:evidence/access-log [prior]}
          entry  {:access-log/principal "alice"
                  :access-log/action    :read
                  :access-log/timestamp (java.time.Instant/now)}
          result (collector/append-access-log-entry bundle entry)]
      (is (= 2 (count (:evidence/access-log result))))
      (is (= "bob" (:access-log/principal (first (:evidence/access-log result)))))
      (is (= "alice" (:access-log/principal (second (:evidence/access-log result))))))))

(deftest append-access-log-entry-stamps-missing-timestamp
  (testing ":access-log/timestamp is added when absent"
    (let [bundle {:evidence/access-log []}
          entry  {:access-log/principal "carol"
                  :access-log/action    :validate}
          result (collector/append-access-log-entry bundle entry)
          logged (first (:evidence/access-log result))]
      (is (contains? logged :access-log/timestamp))
      (is (instance? java.time.Instant (:access-log/timestamp logged))))))

(deftest append-access-log-entry-preserves-existing-timestamp
  (testing ":access-log/timestamp is not overwritten when already present"
    (let [ts     (java.time.Instant/parse "2024-01-01T00:00:00Z")
          bundle {:evidence/access-log []}
          entry  {:access-log/principal "dave"
                  :access-log/action    :read
                  :access-log/timestamp ts}
          result (collector/append-access-log-entry bundle entry)
          logged (first (:evidence/access-log result))]
      (is (= ts (:access-log/timestamp logged))))))

(deftest append-access-log-entry-initializes-nil-access-log
  (testing "fnil initializes missing :evidence/access-log as a vector"
    (let [bundle {}
          entry  {:access-log/principal "eve"
                  :access-log/action    :read
                  :access-log/timestamp (java.time.Instant/now)}
          result (collector/append-access-log-entry bundle entry)]
      (is (vector? (:evidence/access-log result)))
      (is (= 1 (count (:evidence/access-log result)))))))

;------------------------------------------------------------------------------ Layer 2
;; Compliance defaults via assembly

(deftest assemble-sets-default-data-classification
  (testing "assembled bundle carries schema/default-data-classification when no override"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil)]
      (is (= schema/default-data-classification
             (:evidence/data-classification bundle))))))

(deftest assemble-sets-default-retention-days
  (testing "assembled bundle carries schema/default-retention-days when no override"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil)]
      (is (= schema/default-retention-days
             (get-in bundle [:evidence/retention-policy :retain-days]))))))

(deftest assemble-sets-default-contains-pii-false
  (testing "assembled bundle has :evidence/contains-pii? false by default"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil)]
      (is (false? (:evidence/contains-pii? bundle))))))

;------------------------------------------------------------------------------ Layer 3
;; Compliance overrides via opts

(deftest assemble-opts-override-data-classification
  (testing "opts :compliance can override :evidence/data-classification"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/data-classification :confidential}})]
      (is (= :confidential (:evidence/data-classification bundle))))))

(deftest assemble-opts-override-contains-pii
  (testing "opts :compliance can set :evidence/contains-pii? true"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/contains-pii? true}})]
      (is (true? (:evidence/contains-pii? bundle))))))

(deftest assemble-opts-override-created-by
  (testing "opts :compliance can override :evidence/created-by"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/created-by "operator-alice"}})]
      (is (= "operator-alice" (:evidence/created-by bundle))))))

;------------------------------------------------------------------------------ Layer 4
;; Compliance overrides via workflow-spec

(deftest assemble-spec-override-data-classification
  (testing "workflow-spec :compliance key overrides :evidence/data-classification"
    (let [state  (assoc base-workflow-state
                        :workflow/spec
                        {:intent/type :update
                         :description "classified run"
                         :compliance  {:evidence/data-classification :restricted}})
          bundle (collector/assemble-evidence-bundle workflow-id state nil)]
      (is (= :restricted (:evidence/data-classification bundle))))))

(deftest assemble-spec-overrides-take-priority-over-opts
  (testing "workflow-spec :compliance wins over opts :compliance"
    (let [state  (assoc base-workflow-state
                        :workflow/spec
                        {:intent/type :update
                         :description "spec vs opts"
                         :compliance  {:evidence/data-classification :restricted}})
          bundle (collector/assemble-evidence-bundle
                  workflow-id state nil
                  {:compliance {:evidence/data-classification :confidential}})]
      ;; spec is checked first by extract-compliance-overrides
      (is (= :restricted (:evidence/data-classification bundle))))))

;------------------------------------------------------------------------------ Layer 5
;; Partial retention-policy merge

(deftest assemble-partial-retention-policy-merge
  (testing ":evidence/retention-policy override is merged one level deep"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy {:retain-days 365}}})]
      ;; caller only supplied :retain-days; auto-delete? and legal-hold? survive
      (is (= 365 (get-in bundle [:evidence/retention-policy :retain-days])))
      (is (contains? (:evidence/retention-policy bundle) :auto-delete?))
      (is (contains? (:evidence/retention-policy bundle) :legal-hold?)))))

(deftest assemble-full-retention-policy-override
  (testing "full :evidence/retention-policy override replaces all sub-keys"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy
                                {:retain-days  2555
                                 :auto-delete? false
                                 :legal-hold?  true}}})]
      (is (= 2555 (get-in bundle [:evidence/retention-policy :retain-days])))
      (is (false? (get-in bundle [:evidence/retention-policy :auto-delete?])))
      (is (true?  (get-in bundle [:evidence/retention-policy :legal-hold?]))))))
