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

(ns ai.miniforge.schema.supervisory-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def valid-workflow-run
  {:workflow/id (random-uuid)
   :workflow/key "feature-auth"
   :workflow/status :running
   :workflow/phase :implement
   :workflow/started-at (java.util.Date.)})

(def valid-policy-evaluation
  {:eval/id (random-uuid)
   :eval/pr-id "miniforge/core#42"
   :eval/result :pass
   :eval/rules-applied [:no-force-push :require-tests :require-review]
   :eval/evaluated-at (java.util.Date.)})

(def valid-policy-violation
  {:violation/id (random-uuid)
   :violation/eval-id (random-uuid)
   :violation/rule "require-tests"
   :violation/category :testing
   :violation/severity :high})

(def valid-attention-item
  {:attention/id (random-uuid)
   :attention/severity :medium
   :attention/summary "PR #42 has no test coverage for auth module"
   :attention/source-type :policy
   :attention/source-id "eval-abc-123"})

(def valid-waiver
  {:waiver/id (random-uuid)
   :waiver/eval-id (random-uuid)
   :waiver/actor "chris@miniforge.ai"
   :waiver/reason "Legacy code migration — tests will be added in follow-up PR"
   :waiver/created-at (java.util.Date.)})

(def valid-evidence-bundle
  {:evidence/id (random-uuid)
   :evidence/workflow-id (random-uuid)
   :evidence/entries [{:type :test-result :passed? true :suite "unit"}
                      {:type :coverage :value 0.85 :threshold 0.80}
                      {:type :review :approved? true :reviewer "reviewer-agent"}]})

;------------------------------------------------------------------------------ Layer 1
;; WorkflowRun tests

(deftest valid-workflow-run?-test
  (testing "valid workflow run returns true"
    (is (true? (schema/valid-workflow-run? valid-workflow-run))))

  (testing "missing required key :workflow/key fails"
    (is (false? (schema/valid-workflow-run?
                 (dissoc valid-workflow-run :workflow/key)))))

  (testing "missing required key :workflow/started-at fails"
    (is (false? (schema/valid-workflow-run?
                 (dissoc valid-workflow-run :workflow/started-at)))))

  (testing "missing required key :workflow/phase fails"
    (is (false? (schema/valid-workflow-run?
                 (dissoc valid-workflow-run :workflow/phase)))))

  (testing "invalid status fails"
    (is (false? (schema/valid-workflow-run?
                 (assoc valid-workflow-run :workflow/status :bogus)))))

  (testing "extra keys pass through"
    (is (true? (schema/valid-workflow-run?
                (assoc valid-workflow-run
                       :custom/extra "allowed"
                       :workflow/duration-ms 12345
                       :meta {:foo :bar}))))))

;------------------------------------------------------------------------------ Layer 2
;; PolicyEvaluation tests

(deftest valid-policy-evaluation?-test
  (testing "valid policy evaluation returns true"
    (is (true? (schema/valid-policy-evaluation? valid-policy-evaluation))))

  (testing "missing required key :eval/pr-id fails"
    (is (false? (schema/valid-policy-evaluation?
                 (dissoc valid-policy-evaluation :eval/pr-id)))))

  (testing "missing required key :eval/result fails"
    (is (false? (schema/valid-policy-evaluation?
                 (dissoc valid-policy-evaluation :eval/result)))))

  (testing "missing required key :eval/rules-applied fails"
    (is (false? (schema/valid-policy-evaluation?
                 (dissoc valid-policy-evaluation :eval/rules-applied)))))

  (testing "invalid result enum fails"
    (is (false? (schema/valid-policy-evaluation?
                 (assoc valid-policy-evaluation :eval/result :invalid-result)))))

  (testing "extra keys pass through"
    (is (true? (schema/valid-policy-evaluation?
                (assoc valid-policy-evaluation
                       :eval/notes "Manual override approved"
                       :eval/duration-ms 450))))))

;------------------------------------------------------------------------------ Layer 3
;; PolicyViolation tests

(deftest valid-policy-violation?-test
  (testing "valid policy violation returns true"
    (is (true? (schema/valid-policy-violation? valid-policy-violation))))

  (testing "missing required key :violation/rule fails"
    (is (false? (schema/valid-policy-violation?
                 (dissoc valid-policy-violation :violation/rule)))))

  (testing "missing required key :violation/category fails"
    (is (false? (schema/valid-policy-violation?
                 (dissoc valid-policy-violation :violation/category)))))

  (testing "invalid category fails"
    (is (false? (schema/valid-policy-violation?
                 (assoc valid-policy-violation :violation/category :nonexistent)))))

  (testing "invalid severity fails"
    (is (false? (schema/valid-policy-violation?
                 (assoc valid-policy-violation :violation/severity :mega-critical)))))

  (testing "extra keys pass through"
    (is (true? (schema/valid-policy-violation?
                (assoc valid-policy-violation
                       :violation/message "No tests found for changed files"
                       :violation/line-number 42))))))

;------------------------------------------------------------------------------ Layer 4
;; AttentionItem tests

(deftest valid-attention-item?-test
  (testing "valid attention item returns true"
    (is (true? (schema/valid-attention-item? valid-attention-item))))

  (testing "missing required key :attention/summary fails"
    (is (false? (schema/valid-attention-item?
                 (dissoc valid-attention-item :attention/summary)))))

  (testing "missing required key :attention/source-type fails"
    (is (false? (schema/valid-attention-item?
                 (dissoc valid-attention-item :attention/source-type)))))

  (testing "missing required key :attention/source-id fails"
    (is (false? (schema/valid-attention-item?
                 (dissoc valid-attention-item :attention/source-id)))))

  (testing "invalid severity fails"
    (is (false? (schema/valid-attention-item?
                 (assoc valid-attention-item :attention/severity :apocalyptic)))))

  (testing "invalid source-type fails"
    (is (false? (schema/valid-attention-item?
                 (assoc valid-attention-item :attention/source-type :alien)))))

  (testing "extra keys pass through"
    (is (true? (schema/valid-attention-item?
                (assoc valid-attention-item
                       :attention/created-at (java.util.Date.)
                       :attention/acknowledged? false))))))

;------------------------------------------------------------------------------ Layer 5
;; Waiver tests

(deftest valid-waiver?-test
  (testing "valid waiver returns true"
    (is (true? (schema/valid-waiver? valid-waiver))))

  (testing "missing required key :waiver/actor fails"
    (is (false? (schema/valid-waiver?
                 (dissoc valid-waiver :waiver/actor)))))

  (testing "missing required key :waiver/reason fails"
    (is (false? (schema/valid-waiver?
                 (dissoc valid-waiver :waiver/reason)))))

  (testing "missing required key :waiver/created-at fails"
    (is (false? (schema/valid-waiver?
                 (dissoc valid-waiver :waiver/created-at)))))

  (testing "empty actor string fails"
    (is (false? (schema/valid-waiver?
                 (assoc valid-waiver :waiver/actor "")))))

  (testing "extra keys pass through"
    (is (true? (schema/valid-waiver?
                (assoc valid-waiver
                       :waiver/expires-at (java.util.Date.)
                       :waiver/scope :pr))))))

;------------------------------------------------------------------------------ Layer 6
;; EvidenceBundle tests

(deftest valid-evidence-bundle?-test
  (testing "valid evidence bundle returns true"
    (is (true? (schema/valid-evidence-bundle? valid-evidence-bundle))))

  (testing "missing required key :evidence/workflow-id fails"
    (is (false? (schema/valid-evidence-bundle?
                 (dissoc valid-evidence-bundle :evidence/workflow-id)))))

  (testing "missing required key :evidence/entries fails"
    (is (false? (schema/valid-evidence-bundle?
                 (dissoc valid-evidence-bundle :evidence/entries)))))

  (testing "empty entries vector is valid"
    (is (true? (schema/valid-evidence-bundle?
                (assoc valid-evidence-bundle :evidence/entries [])))))

  (testing "extra keys pass through"
    (is (true? (schema/valid-evidence-bundle?
                (assoc valid-evidence-bundle
                       :evidence/created-at (java.util.Date.)
                       :evidence/summary "All checks passed"))))))

;------------------------------------------------------------------------------ Layer 7
;; Cross-cutting validation tests

(deftest explain-supervisory-test
  (testing "explain returns nil for valid data"
    (is (nil? (schema/explain schema/WorkflowRun valid-workflow-run)))
    (is (nil? (schema/explain schema/PolicyEvaluation valid-policy-evaluation)))
    (is (nil? (schema/explain schema/PolicyViolation valid-policy-violation)))
    (is (nil? (schema/explain schema/AttentionItem valid-attention-item)))
    (is (nil? (schema/explain schema/Waiver valid-waiver)))
    (is (nil? (schema/explain schema/EvidenceBundle valid-evidence-bundle))))

  (testing "explain returns error map for invalid data"
    (let [errors (schema/explain schema/WorkflowRun {:workflow/id "not-a-uuid"})]
      (is (map? errors))
      (is (contains? errors :workflow/id)))))

(deftest validate-supervisory-test
  (testing "validate returns data for valid input"
    (is (= valid-workflow-run
           (schema/validate schema/WorkflowRun valid-workflow-run))))

  (testing "validate throws for invalid input"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Schema validation failed"
                          (schema/validate schema/WorkflowRun {})))))

(deftest enum-values-test
  (testing "eval-results contains expected values"
    (is (= [:pass :fail :warn :skip] schema/eval-results)))

  (testing "violation-categories contains expected values"
    (is (some #{:security} schema/violation-categories))
    (is (some #{:testing} schema/violation-categories))
    (is (some #{:architecture} schema/violation-categories)))

  (testing "violation-severities contains expected values"
    (is (= [:info :low :medium :high :critical] schema/violation-severities)))

  (testing "attention-source-types contains expected values"
    (is (some #{:workflow} schema/attention-source-types))
    (is (some #{:human} schema/attention-source-types))))

;------------------------------------------------------------------------------ Layer 8
;; All-required-keys-missing tests (comprehensive)

(deftest empty-map-fails-all-schemas-test
  (testing "empty map fails all supervisory schemas"
    (is (false? (schema/valid-workflow-run? {})))
    (is (false? (schema/valid-policy-evaluation? {})))
    (is (false? (schema/valid-policy-violation? {})))
    (is (false? (schema/valid-attention-item? {})))
    (is (false? (schema/valid-waiver? {})))
    (is (false? (schema/valid-evidence-bundle? {})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.schema.supervisory-test)

  :leave-this-here)
