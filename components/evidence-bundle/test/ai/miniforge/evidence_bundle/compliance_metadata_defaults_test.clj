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

(ns ai.miniforge.evidence-bundle.compliance-metadata-defaults-test
  "Tests that assemble-evidence-bundle applies correct compliance defaults.

   Scope: default values for all six compliance fields when no :compliance
   override is present on the workflow-spec or opts."
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
                     :description "compliance-defaults test workflow"}
   :workflow/phases {}})

;------------------------------------------------------------------------------ Layer 1
;; Default Compliance Metadata

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
  (testing "assembled bundle has a retention policy map with schema-defined defaults"
    (let [bundle    (collector/assemble-evidence-bundle
                     workflow-id base-workflow-state nil)
          retention (:evidence/retention-policy bundle)]
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

(comment
  (clojure.test/run-tests)
  :leave-this-here)
