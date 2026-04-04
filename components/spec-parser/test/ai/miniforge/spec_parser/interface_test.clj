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

(ns ai.miniforge.spec-parser.interface-test
  "Tests for spec-parser component: schema validation, normalization, and parsing."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.spec-parser.interface :as spec-parser]
   [ai.miniforge.spec-parser.schema :as schema]
   [malli.core :as m]
   [malli.generator :as mg]))

;------------------------------------------------------------------------------ Layer 0: Schema Tests
;; Verify Malli schemas accept/reject correctly

(deftest spec-input-schema-test
  (testing "minimal valid SpecInput"
    (is (true? (m/validate schema/SpecInput
                           {:spec/title "Refactor logging"
                            :spec/description "Extract logging"}))))

  (testing "full valid SpecInput"
    (is (true? (m/validate schema/SpecInput
                           {:spec/title "Refactor logging"
                            :spec/description "Extract logging to separate module"
                            :spec/intent {:type :refactor :scope ["src/logging"]}
                            :spec/constraints ["no-breaking-changes"]
                            :spec/tags [:refactoring]
                            :spec/acceptance-criteria ["tests pass"]
                            :spec/repo-url "https://github.com/example/repo"
                            :spec/branch "feat/logging"
                            :spec/llm-backend :anthropic
                            :spec/sandbox true
                            :workflow/type :full-sdlc
                            :workflow/version "2.0.0"}))))

  (testing "missing title rejects"
    (is (false? (m/validate schema/SpecInput
                            {:spec/description "No title"}))))

  (testing "missing description rejects"
    (is (false? (m/validate schema/SpecInput
                            {:spec/title "No desc"}))))

  (testing "empty title rejects"
    (is (false? (m/validate schema/SpecInput
                            {:spec/title "" :spec/description "D"})))))

(deftest spec-payload-schema-test
  (testing "valid normalized SpecPayload"
    (is (true? (m/validate schema/SpecPayload
                           {:spec/title "T"
                            :spec/description "D"
                            :spec/intent {:type :general}
                            :spec/constraints []
                            :spec/tags []
                            :spec/workflow-version "latest"
                            :spec/raw-data {}}))))

  (testing "SpecPayload with all optional fields"
    (is (true? (m/validate schema/SpecPayload
                           {:spec/title "T"
                            :spec/description "D"
                            :spec/intent {:type :feature}
                            :spec/constraints ["c1"]
                            :spec/tags [:t1]
                            :spec/workflow-type :full-sdlc
                            :spec/workflow-version "2.0.0"
                            :spec/raw-data {}
                            :spec/acceptance-criteria ["a1"]
                            :spec/code-artifact {:code/id "abc" :code/files []}
                            :spec/repo-url "https://example.com"
                            :spec/branch "main"
                            :spec/llm-backend :anthropic
                            :spec/sandbox true
                            :spec/plan-tasks [{:task/id :step-1
                                               :task/description "Do stuff"}]})))))

(deftest malli-generator-round-trip-test
  (testing "generated SpecInput values validate as SpecInput"
    (doseq [_ (range 5)]
      (let [sample (mg/generate schema/SpecInput)]
        (is (m/validate schema/SpecInput sample)
            (str "Generated SpecInput should validate: " (pr-str sample)))))))

;------------------------------------------------------------------------------ Layer 1: Normalization Tests

(deftest normalize-canonical-format-test
  (testing "canonical :spec/* input normalizes correctly"
    (let [input  {:spec/title "Refactor logging"
                  :spec/description "Extract logging to separate module"
                  :spec/intent {:type :refactor :scope ["src/logging"]}
                  :spec/constraints ["no-breaking-changes"]
                  :spec/acceptance-criteria ["tests pass" "coverage maintained"]
                  :spec/tags [:refactoring]
                  :spec/repo-url "https://github.com/example/repo"
                  :spec/branch "feat/logging"
                  :spec/llm-backend :anthropic
                  :spec/sandbox true
                  :workflow/type :full-sdlc
                  :workflow/version "2.0.0"}
          result (spec-parser/normalize-spec input)]
      (is (= "Refactor logging" (:spec/title result)))
      (is (= "Extract logging to separate module" (:spec/description result)))
      (is (= {:type :refactor :scope ["src/logging"]} (:spec/intent result)))
      (is (= ["no-breaking-changes"] (:spec/constraints result)))
      (is (= ["tests pass" "coverage maintained"] (:spec/acceptance-criteria result)))
      (is (= [:refactoring] (:spec/tags result)))
      (is (= :full-sdlc (:spec/workflow-type result)))
      (is (= "2.0.0" (:spec/workflow-version result)))
      (is (= "https://github.com/example/repo" (:spec/repo-url result)))
      (is (= "feat/logging" (:spec/branch result)))
      (is (= :anthropic (:spec/llm-backend result)))
      (is (true? (:spec/sandbox result)))
      (is (= input (:spec/raw-data result))))))

(deftest normalize-plan-tasks-test
  (testing ":spec/plan-tasks passes through"
    (let [tasks [{:task/id :step-1 :task/description "First" :task/type :implement}
                 {:task/id :step-2 :task/description "Second" :task/dependencies [:step-1]}]
          result (spec-parser/normalize-spec
                  {:spec/title "Multi-phase"
                   :spec/description "Build in phases"
                   :spec/plan-tasks tasks
                   :workflow/type :canonical-sdlc})]
      (is (= 2 (count (:spec/plan-tasks result))))
      (is (= :step-1 (:task/id (first (:spec/plan-tasks result))))))))

;------------------------------------------------------------------------------ Layer 2: Defaults Tests

(deftest defaults-test
  (testing "intent defaults to {:type :general}"
    (let [result (spec-parser/normalize-spec {:spec/title "T" :spec/description "D"})]
      (is (= {:type :general} (:spec/intent result)))))

  (testing "constraints default to empty vector"
    (let [result (spec-parser/normalize-spec {:spec/title "T" :spec/description "D"})]
      (is (= [] (:spec/constraints result)))))

  (testing "tags default to empty vector"
    (let [result (spec-parser/normalize-spec {:spec/title "T" :spec/description "D"})]
      (is (= [] (:spec/tags result)))))

  (testing "workflow-version defaults to \"latest\""
    (let [result (spec-parser/normalize-spec {:spec/title "T" :spec/description "D"})]
      (is (= "latest" (:spec/workflow-version result)))))

  (testing "optional keys absent when not provided"
    (let [result (spec-parser/normalize-spec {:spec/title "T" :spec/description "D"})]
      (is (nil? (:spec/acceptance-criteria result)))
      (is (nil? (:spec/code-artifact result)))
      (is (nil? (:spec/repo-url result)))
      (is (nil? (:spec/branch result)))
      (is (nil? (:spec/llm-backend result)))
      (is (nil? (:spec/sandbox result)))
      (is (nil? (:spec/plan-tasks result))))))

;------------------------------------------------------------------------------ Layer 3: Validation Error Tests

(deftest validation-errors-test
  (testing "spec must be a map"
    (is (thrown-with-msg? Exception #"must be a map"
          (spec-parser/normalize-spec "not a map"))))

  (testing "spec must have :spec/title"
    (is (thrown-with-msg? Exception #"must have :spec/title"
          (spec-parser/normalize-spec {:spec/description "No title"}))))

  (testing "spec must have :spec/description"
    (is (thrown-with-msg? Exception #"must have :spec/description"
          (spec-parser/normalize-spec {:spec/title "No desc"})))))

(deftest validate-spec-with-malli-test
  (testing "valid normalized spec passes Malli validation"
    (let [result (spec-parser/validate-spec
                  {:spec/title "T"
                   :spec/description "D"
                   :spec/intent {:type :feature}
                   :spec/constraints []
                   :spec/tags []
                   :spec/workflow-version "latest"
                   :spec/raw-data {}})]
      (is (true? (:valid? result)))))

  (testing "minimal normalized spec passes Malli validation"
    (let [normalized (spec-parser/normalize-spec {:spec/title "T" :spec/description "D"})
          result     (spec-parser/validate-spec normalized)]
      (is (true? (:valid? result)))))

  (testing "invalid spec fails Malli validation with errors"
    (let [result (spec-parser/validate-spec {:spec/title "T"})]
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

;------------------------------------------------------------------------------ Layer 4: Schema Validation Helpers

(deftest schema-validation-helpers-test
  (testing "valid-spec-input? accepts canonical input"
    (is (true? (spec-parser/valid-spec-input?
                {:spec/title "T" :spec/description "D"}))))

  (testing "valid-spec-input? rejects missing required fields"
    (is (false? (spec-parser/valid-spec-input?
                 {:spec/title "T"}))))

  (testing "explain-spec-input returns nil for valid input"
    (is (nil? (spec-parser/explain-spec-input
               {:spec/title "T" :spec/description "D"}))))

  (testing "explain-spec-input returns errors for invalid input"
    (is (some? (spec-parser/explain-spec-input
                {:spec/title "T"})))))
