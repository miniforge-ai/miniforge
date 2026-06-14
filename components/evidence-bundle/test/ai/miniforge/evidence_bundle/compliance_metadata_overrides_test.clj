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

(ns ai.miniforge.evidence-bundle.compliance-metadata-overrides-test
  "Tests that assemble-evidence-bundle applies compliance overrides correctly.

   Scope: workflow-spec :compliance overrides, opts :compliance overrides, and
   partial :evidence/retention-policy merging with schema-defined defaults."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.collector :as collector]
   [ai.miniforge.evidence-bundle.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures and Named Constants

(def ^:private workflow-id
  #uuid "cafebabe-0000-0000-0000-000000000002")

(def ^:private base-workflow-state
  {:workflow/status :completed
   :workflow/spec   {:intent/type :update
                     :description "compliance-overrides test workflow"}
   :workflow/phases {}})

(def ^:private one-year-retention-days
  "Retention period used in override tests to verify a non-default value
   is accepted and survives the round-trip through assemble-evidence-bundle."
  365)

(def ^:private seven-year-retention-days
  "Seven-year retention (2555 days) used to test full override of all three
   retention-policy sub-keys simultaneously. SOX mandates 7 years."
  2555)

;------------------------------------------------------------------------------ Layer 1
;; Overrides from Workflow-Spec and Opts

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

;------------------------------------------------------------------------------ Layer 2
;; Partial Retention-Policy Merging

(deftest assemble-partial-retention-policy-merges-legal-hold
  (testing "partial override with only :legal-hold? true merges with defaults"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy {:legal-hold? true}}})]
      (is (true? (get-in bundle [:evidence/retention-policy :legal-hold?]))
          ":legal-hold? override must take effect")
      (is (= schema/default-retention-days
             (get-in bundle [:evidence/retention-policy :retain-days]))
          ":retain-days must survive a partial :legal-hold? override")
      (is (true? (get-in bundle [:evidence/retention-policy :auto-delete?]))
          ":auto-delete? must survive a partial :legal-hold? override"))))

(deftest assemble-partial-retention-policy-merges-retain-days
  (testing "partial override with only :retain-days merges with defaults"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy
                                {:retain-days one-year-retention-days}}})]
      (is (= one-year-retention-days
             (get-in bundle [:evidence/retention-policy :retain-days])))
      (is (contains? (:evidence/retention-policy bundle) :auto-delete?))
      (is (contains? (:evidence/retention-policy bundle) :legal-hold?)))))

(deftest assemble-full-retention-policy-override
  (testing "full :evidence/retention-policy override replaces all three sub-keys"
    (let [bundle (collector/assemble-evidence-bundle
                  workflow-id base-workflow-state nil
                  {:compliance {:evidence/retention-policy
                                {:retain-days  seven-year-retention-days
                                 :auto-delete? false
                                 :legal-hold?  true}}})]
      (is (= seven-year-retention-days
             (get-in bundle [:evidence/retention-policy :retain-days])))
      (is (false? (get-in bundle [:evidence/retention-policy :auto-delete?])))
      (is (true?  (get-in bundle [:evidence/retention-policy :legal-hold?]))))))

(comment
  (clojure.test/run-tests)
  :leave-this-here)
