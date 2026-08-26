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
(ns ai.miniforge.evidence-bundle.assembly-integration-test
  "Integration test for evidence bundle assembly.

   Tests that evidence bundles are correctly assembled from workflow state,
   that required fields are present, that phase evidence is collected,
   and that bundle validation catches incomplete bundles.

   All externals are mocked — no artifact store, no event stream, no network."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.content-hash.interface :as hash]
   [ai.miniforge.evidence-bundle.collector :as collector]
   [ai.miniforge.evidence-bundle.collectors :as collectors]
   [ai.miniforge.evidence-bundle.outcome :as outcome]
   [ai.miniforge.evidence-bundle.phases :as phases]
   [ai.miniforge.evidence-bundle.schema :as schema]
   [ai.miniforge.evidence-bundle.schema.domain :as domain]
   [ai.miniforge.evidence-bundle.schema.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

;; Factory functions
(defn ^{:stratum 0} make-workflow-id
  "Create a random workflow UUID."
  []
  (random-uuid))

(defn ^{:stratum 0} make-timestamp
  "Create a timestamp for test data."
  []
  (java.time.Instant/now))

(defn ^{:stratum 0} make-workflow-spec
  "Create a workflow specification."
  [& {:keys [intent-type description]
      :or {intent-type :create
           description "Implement new feature"}}]
  {:intent/type intent-type
   :description description
   :business-reason "Improve user experience"
   :constraints [{:constraint/type :pre
                  :constraint/description "No breaking changes"}]
   :author "test-agent"})

(deftest ^{:stratum 0} extract-intent-defaults-test
  (testing "Intent extraction handles missing fields with defaults"
    (let [minimal-spec {}
          intent (collector/extract-intent minimal-spec)]
      (is (= :update (:intent/type intent))
          "Default intent type should be :update")
      (is (string? (:intent/description intent))
          "Description should default to empty string")
      (is (string? (:intent/business-reason intent))
          "Business reason should have a default"))))

;; Bundle validation
(deftest ^{:stratum 0} validate-bundle-template-test
  (testing "Bundle template passes schema validation for present fields"
    (let [template (schema/create-evidence-bundle-template)]
      (is (uuid? (:evidence-bundle/id template))
          "Template should have a UUID")
      (is (inst? (:evidence-bundle/created-at template))
          "Template should have a timestamp")
      (is (vector? (:evidence/policy-checks template))
          "Template should have policy-checks vector")
      (is (vector? (:evidence/tool-invocations template))
          "Template should have tool-invocations vector"))))

(deftest ^{:stratum 0} validate-incomplete-bundle-test
  (testing "Schema validation catches missing required fields"
    (let [incomplete-bundle {:evidence-bundle/id (random-uuid)}
          result (validation/validate-schema schema/evidence-bundle-schema
                                             incomplete-bundle)]
      (is (false? (:valid? result))
          "Incomplete bundle should fail validation")
      (is (pos? (count (:errors result)))
          "Should report missing field errors"))))

;; Content hashing
(deftest ^{:stratum 0} content-hash-deterministic-test
  (testing "Same content produces same hash"
    (let [content {:key "value" :number 42}
          hash1 (hash/content-hash content)
          hash2 (hash/content-hash content)]
      (is (= hash1 hash2)
          "Hash should be deterministic")
      (is (= 64 (count hash1))
          "SHA-256 hex hash should be 64 characters"))))

(deftest ^{:stratum 0} content-hash-different-content-test
  (testing "Different content produces different hash"
    (let [hash1 (hash/content-hash {:key "value-a"})
          hash2 (hash/content-hash {:key "value-b"})]
      (is (not= hash1 hash2)
          "Different content should produce different hashes"))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} make-phase-data
  "Create phase execution data for a single phase."
  [& {:keys [agent status started-at completed-at duration-ms artifacts]
      :or {agent :implementer
           status :success
           duration-ms 5000
           artifacts []}}]
  (let [now (make-timestamp)
        start (or started-at now)
        end (or completed-at (.plusMillis now duration-ms))]
    {:agent agent
     :status status
     :started-at start
     :completed-at end
     :duration-ms duration-ms
     :artifacts artifacts
     :output {:summary "Phase completed successfully"
              :metrics {:tokens 1000 :duration-ms duration-ms}}}))

(defn ^{:stratum 1} make-workflow-state
  "Create a complete workflow state for bundle assembly."
  [& {:keys [workflow-id status phases gate-results pr-info error
             tool-invocations pack-promotions]
      :or {status :completed
           phases {}
           gate-results []
           tool-invocations []
           pack-promotions []}}]
  (cond-> {:workflow/id (or workflow-id (make-workflow-id))
           :workflow/status status
           :workflow/spec (make-workflow-spec)
           :workflow/phases phases
           :workflow/gate-results gate-results
           :workflow/tool-invocations tool-invocations
           :workflow/pack-promotions pack-promotions}
    pr-info (assoc :workflow/pr-info pr-info)
    error (assoc :workflow/error error)))

(defn ^{:stratum 1} make-gate-result
  "Create a gate result record."
  [& {:keys [pack-id phase passed?]
      :or {pack-id "test-pack" phase :implement passed? true}}]
  {:pack-id pack-id
   :pack-version "1.0.0"
   :phase phase
   :checked-at (make-timestamp)
   :violations (if passed? [] [{:rule-id "test-rule" :severity :medium
                                 :message "Test violation"}])
   :passed? passed?
   :duration-ms 100})

;; Intent extraction
(deftest ^{:stratum 1} extract-intent-test
  (testing "Intent is extracted from workflow specification"
    (let [spec (make-workflow-spec :intent-type :create
                                   :description "Build auth module")
          intent (collector/extract-intent spec)]
      (is (= :create (:intent/type intent))
          "Intent type should match spec")
      (is (= "Build auth module" (:intent/description intent))
          "Description should be extracted")
      (is (string? (:intent/business-reason intent))
          "Business reason should be present")
      (is (inst? (:intent/declared-at intent))
          "Declared-at timestamp should be present"))))

(deftest ^{:stratum 1} validate-intent-schema-test
  (testing "Intent evidence schema validates required fields"
    (let [valid-intent {:intent/type :create
                        :intent/description "Build feature"
                        :intent/business-reason "User demand"
                        :intent/constraints []
                        :intent/declared-at (make-timestamp)}
          result (validation/validate-schema domain/intent-schema valid-intent)]
      (is (true? (:valid? result))
          "Valid intent should pass schema validation"))

    (let [invalid-intent {:intent/type :create}
          result (validation/validate-schema domain/intent-schema invalid-intent)]
      (is (false? (:valid? result))
          "Intent missing required fields should fail"))))

;------------------------------------------------------------------------------ Layer 2

;; Phase evidence collection
(deftest ^{:stratum 2} collect-single-phase-evidence-test
  (testing "Evidence is collected for an executed phase"
    (let [workflow-state (make-workflow-state
                          :phases {:implement (make-phase-data :agent :implementer)})
          evidence (phases/collect-phase-evidence workflow-state :implement)]
      (is (some? evidence)
          "Phase evidence should be returned")
      (is (= :implement (:phase/name evidence))
          "Phase name should be :implement")
      (is (= :implementer (:phase/agent evidence))
          "Agent should match phase data")
      (is (uuid? (:phase/agent-instance-id evidence))
          "Agent instance ID should be a UUID")
      (is (inst? (:phase/started-at evidence))
          "Started-at should be present")
      (is (inst? (:phase/completed-at evidence))
          "Completed-at should be present")
      (is (pos? (:phase/duration-ms evidence))
          "Duration should be positive")
      (is (map? (:phase/output evidence))
          "Output should be a map"))))

(deftest ^{:stratum 2} collect-phase-evidence-missing-phase-test
  (testing "Returns nil for unexecuted phase"
    (let [workflow-state (make-workflow-state :phases {})
          evidence (phases/collect-phase-evidence workflow-state :implement)]
      (is (nil? evidence)
          "Should return nil for unexecuted phase"))))

(deftest ^{:stratum 2} collect-all-phases-test
  (testing "Evidence is collected for all executed phases"
    (let [workflow-state (make-workflow-state
                          :phases {:plan (make-phase-data :agent :planner)
                                   :implement (make-phase-data :agent :implementer)
                                   :review (make-phase-data :agent :reviewer)})
          all-evidence (collector/collect-all-phases workflow-state)]
      (is (= 3 (count all-evidence))
          "Should have evidence for 3 executed phases")
      (is (contains? all-evidence :evidence/plan)
          "Should have plan evidence")
      (is (contains? all-evidence :evidence/implement)
          "Should have implement evidence")
      (is (contains? all-evidence :evidence/review)
          "Should have review evidence")
      (is (not (contains? all-evidence :evidence/verify))
          "Should not have evidence for unexecuted verify phase"))))

;; Outcome evidence
(deftest ^{:stratum 2} outcome-evidence-success-test
  (testing "Outcome evidence for successful workflow"
    (let [workflow-state (make-workflow-state
                          :status :completed
                          :pr-info {:number 42
                                    :url "https://github.com/org/repo/pull/42"
                                    :status :merged
                                    :merged-at (make-timestamp)})
          outcome (outcome/build-outcome-evidence workflow-state)]
      (is (true? (:outcome/success outcome))
          "Outcome should indicate success")
      (is (= 42 (:outcome/pr-number outcome))
          "PR number should be present")
      (is (= "https://github.com/org/repo/pull/42" (:outcome/pr-url outcome))
          "PR URL should be present"))))

(deftest ^{:stratum 2} outcome-evidence-failure-test
  (testing "Outcome evidence for failed workflow"
    (let [workflow-state (make-workflow-state
                          :status :failed
                          :error {:message "Compilation error"
                                  :phase :implement})
          outcome (outcome/build-outcome-evidence workflow-state)]
      (is (false? (:outcome/success outcome))
          "Outcome should indicate failure")
      (is (= "Compilation error" (:outcome/error-message outcome))
          "Error message should be preserved")
      (is (= :implement (:outcome/error-phase outcome))
          "Error phase should be preserved"))))

;; Policy check collection
(deftest ^{:stratum 2} collect-policy-checks-test
  (testing "Policy checks are collected from gate results"
    (let [workflow-state (make-workflow-state
                          :gate-results [(make-gate-result :phase :implement :passed? true)
                                         (make-gate-result :phase :review :passed? false)])
          checks (collectors/collect-policy-checks workflow-state)]
      (is (= 2 (count checks))
          "Should collect both gate results")
      (is (true? (:policy-check/passed? (first checks)))
          "First check should be passing")
      (is (false? (:policy-check/passed? (second checks)))
          "Second check should be failing")
      (is (every? inst? (map :policy-check/checked-at checks))
          "All checks should have timestamps"))))

(deftest ^{:stratum 2} collect-policy-checks-empty-test
  (testing "Empty gate results produce empty vector"
    (let [workflow-state (make-workflow-state :gate-results [])
          checks (collectors/collect-policy-checks workflow-state)]
      (is (empty? checks)
          "Should return empty vector when no gate results"))))

;; Bundle assembly (without artifact store)
(deftest ^{:stratum 2} assemble-bundle-required-fields-test
  (testing "Assembled bundle contains all required fields"
    (let [workflow-id (make-workflow-id)
          workflow-state (make-workflow-state
                          :workflow-id workflow-id
                          :phases {:implement (make-phase-data)})
          bundle (collector/assemble-evidence-bundle
                  workflow-id workflow-state nil)]
      ;; Required fields per evidence-bundle-schema
      (is (uuid? (:evidence-bundle/id bundle))
          "Bundle ID should be a UUID")
      (is (= workflow-id (:evidence-bundle/workflow-id bundle))
          "Workflow ID should match")
      (is (inst? (:evidence-bundle/created-at bundle))
          "Created-at should be present")
      (is (string? (:evidence-bundle/version bundle))
          "Version should be a string")
      (is (map? (:evidence/intent bundle))
          "Intent evidence should be present")
      (is (vector? (:evidence/policy-checks bundle))
          "Policy checks should be a vector")
      (is (map? (:evidence/outcome bundle))
          "Outcome evidence should be present")
      (is (string? (:evidence/content-hash bundle))
          "Content hash should be present"))))

(deftest ^{:stratum 2} assemble-bundle-with-phases-test
  (testing "Bundle includes phase evidence for executed phases"
    (let [workflow-id (make-workflow-id)
          workflow-state (make-workflow-state
                          :workflow-id workflow-id
                          :phases {:plan (make-phase-data :agent :planner)
                                   :implement (make-phase-data :agent :implementer)
                                   :verify (make-phase-data :agent :tester)
                                   :review (make-phase-data :agent :reviewer)})
          bundle (collector/assemble-evidence-bundle
                  workflow-id workflow-state nil)]
      (is (some? (:evidence/plan bundle))
          "Plan evidence should be in bundle")
      (is (some? (:evidence/implement bundle))
          "Implement evidence should be in bundle")
      (is (some? (:evidence/verify bundle))
          "Verify evidence should be in bundle")
      (is (some? (:evidence/review bundle))
          "Review evidence should be in bundle")
      (is (nil? (:evidence/release bundle))
          "Release evidence should not be in bundle (not executed)"))))

;; Auto-collect evidence
(deftest ^{:stratum 2} auto-collect-completed-workflow-test
  (testing "Auto-collect creates bundle for completed workflow"
    (let [workflow-id (make-workflow-id)
          workflow-state (make-workflow-state
                          :workflow-id workflow-id
                          :status :completed)
          bundle (collector/auto-collect-evidence
                  workflow-id workflow-state nil)]
      (is (some? bundle)
          "Bundle should be created for completed workflow")
      (is (uuid? (:evidence-bundle/id bundle))
          "Bundle should have an ID"))))

(deftest ^{:stratum 2} auto-collect-failed-workflow-test
  (testing "Auto-collect creates bundle for failed workflow"
    (let [workflow-id (make-workflow-id)
          workflow-state (make-workflow-state
                          :workflow-id workflow-id
                          :status :failed
                          :error {:message "Build failed"})
          bundle (collector/auto-collect-evidence
                  workflow-id workflow-state nil)]
      (is (some? bundle)
          "Bundle should be created even for failed workflows"))))

(deftest ^{:stratum 2} auto-collect-in-progress-workflow-test
  (testing "Auto-collect returns nil for in-progress workflow"
    (let [workflow-id (make-workflow-id)
          workflow-state (make-workflow-state
                          :workflow-id workflow-id
                          :status :running)
          bundle (collector/auto-collect-evidence
                  workflow-id workflow-state nil)]
      (is (nil? bundle)
          "Bundle should not be created for running workflow"))))

;; Rules-applied collection
(deftest ^{:stratum 2} collect-rules-applied-test
  (testing "Rules-applied entries are collected from phase manifests"
    (let [rule-id (random-uuid)
          workflow-state (make-workflow-state
                          :phases {:implement
                                   (assoc (make-phase-data)
                                          :rules-manifest
                                          [{:id rule-id
                                            :title "No magic numbers"
                                            :role :quality
                                            :tags-matched [:clojure]
                                            :score 0.9}])})
          rules (collectors/collect-rules-applied workflow-state)]
      (is (= 1 (count rules))
          "Should collect one rule")
      (is (= rule-id (:id (first rules)))
          "Rule ID should match")
      (is (= :implement (:phase (first rules)))
          "Phase annotation should be :implement"))))

(deftest ^{:stratum 2} collect-rules-applied-empty-test
  (testing "Returns empty vector when no rules manifests exist"
    (let [workflow-state (make-workflow-state :phases {:implement (make-phase-data)})
          rules (collectors/collect-rules-applied workflow-state)]
      (is (empty? rules)
          "Should return empty vector"))))

;; Tool invocation collection
(deftest ^{:stratum 2} collect-tool-invocations-test
  (testing "Tool invocations are collected from workflow state"
    (let [invocation {:tool/id :gh-pr-create
                      :tool/invoked-at (make-timestamp)
                      :tool/duration-ms 500
                      :tool/args {:title "Test PR"}}
          workflow-state (make-workflow-state)
          state-with-tools (assoc workflow-state
                                  :workflow/tool-invocations [invocation])
          invocations (collectors/collect-tool-invocations state-with-tools)]
      (is (= 1 (count invocations))
          "Should collect one invocation")
      (is (= :gh-pr-create (:tool/id (first invocations)))
          "Tool ID should match"))))

;; N6 §7.2 — sensitive data in assembled bundles
(deftest ^{:stratum 2} assembled-bundle-is-redacted-before-sealing-test
  (testing "a secret in phase output does not survive into the bundle"
    ;; N6.SD.4: flagging alone does not satisfy N3 §8.1. Before this, the
    ;; scanner recorded that a bundle held a secret and stored it anyway.
    (let [secret "AKIAIOSFODNN7EXAMPLE"
          phase  (assoc-in (make-phase-data)
                           [:output :summary]
                           (str "Deployed with " secret))
          bundle (collector/assemble-evidence-bundle
                  (make-workflow-id)
                  (make-workflow-state :phases {:implement phase})
                  nil)
          text   (pr-str bundle)]
      (is (not (str/includes? text secret))
          "the secret must not survive anywhere in the bundle")
      (is (str/includes? text "[REDACTED]")
          "the marker records that a value was removed")))

  (testing "the compliance finding survives the redaction that follows it"
    ;; Scan first, then redact: that a secret *was* present is the
    ;; auditable fact. Redacting first would erase the evidence of it.
    (let [phase  (assoc-in (make-phase-data)
                           [:output :summary]
                           "Deployed with AKIAIOSFODNN7EXAMPLE")
          bundle (collector/assemble-evidence-bundle
                  (make-workflow-id)
                  (make-workflow-state :phases {:implement phase})
                  nil)]
      (is (some #(= :aws-access-key (:finding/type %))
                (:compliance/sensitive-findings bundle))
          "the scan finding is still reported after redaction")))

  (testing "the content hash binds the redacted bundle, not the original"
    ;; N6 §7.2 seals only after the substitution. Hashing first would
    ;; bind the bundle to a form that no longer exists.
    (let [phase  (assoc-in (make-phase-data)
                           [:output :summary]
                           "Deployed with AKIAIOSFODNN7EXAMPLE")
          bundle (collector/assemble-evidence-bundle
                  (make-workflow-id)
                  (make-workflow-state :phases {:implement phase})
                  nil)]
      (is (= (:evidence/content-hash bundle)
             (hash/content-hash (dissoc bundle :evidence/content-hash)))
          "the stored hash recomputes over the redacted bundle")))

  (testing "token counts in phase metrics survive"
    (let [bundle (collector/assemble-evidence-bundle
                  (make-workflow-id)
                  (make-workflow-state :phases {:implement (make-phase-data)})
                  nil)]
      (is (str/includes? (pr-str bundle) ":tokens 1000")
          "an LLM token count is not a bearer token"))))
