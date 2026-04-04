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

(ns ai.miniforge.cli.workflow-selector-test
  "Tests for intelligent workflow selection."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-selector :as ws]))

;------------------------------------------------------------------------------ Test Data

(def multi-phase-refactor-spec
  "Emojui-style spec with 6 PRs and stratified design"
  {:spec/title "Memento Views Refactor"
   :spec/description "Multi-phase refactor of 5 memory view pages to follow stratified design"
   :spec/raw-data
   {:type :refactoring
    :implementation-plan
    {:pr-2-shared-components {:branch "feature/memento-shared-components"}
     :pr-3-stream-view {:branch "feature/memento-stream-view" :dependencies [:pr-2-shared-components]}
     :pr-4-morning-view {:branch "feature/memento-morning-view" :dependencies [:pr-2-shared-components]}
     :pr-5-garden-view {:branch "feature/memento-garden-view" :dependencies [:pr-2-shared-components]}
     :pr-6-constellation-view {:branch "feature/memento-constellation-view"}
     :pr-7-heatmap-view {:branch "feature/memento-heatmap-view"}}}
   :spec/constraints ["Follow stratified design" "≤400 lines per file (Rule 720)"]})

(def bugfix-spec
  "Simple bug fix spec"
  {:spec/title "Fix authentication timeout"
   :spec/description "Fix bug where auth token expires too quickly"
   :spec/intent {:type :bugfix}})

(def docs-spec
  "Documentation-only spec"
  {:spec/title "Update API documentation"
   :spec/description "Update API docs only"
   :spec/intent {:type :docs}})

(def large-feature-spec
  "Large feature without explicit phases"
  {:spec/title "Add new payment system"
   :spec/description "Large comprehensive payment processing system with Stripe integration"
   :spec/raw-data {:type :feature}})

(def small-feature-spec
  "Small feature spec"
  {:spec/title "Add tooltip"
   :spec/description "Add simple tooltip to button"
   :spec/raw-data {:type :feature}})

(def explicit-workflow-spec
  "Spec with explicit workflow-type"
  {:spec/title "Custom workflow"
   :spec/workflow-type :simple-test-v1
   :spec/description "Test with explicit workflow"})

(def refactor-with-rule-210-spec
  "Refactoring spec mentioning Rule 210"
  {:spec/title "Stratified refactor"
   :spec/description "Refactor to use layered architecture"
   :spec/raw-data {:type :refactoring}
   :spec/constraints ["≤3 layers (Rule 210)" "Stratified design"]})

(def unknown-spec
  "Spec with minimal information"
  {:spec/title "Do something"
   :spec/description "Something needs to be done"})

;------------------------------------------------------------------------------ Layer 0 Tests
;; Spec analysis

(deftest analyze-spec-test
  (testing "analyze-spec extracts features from multi-phase refactor"
    (let [features (ws/analyze-spec multi-phase-refactor-spec)]
      (is (= :refactoring (:type features)))
      (is (= 6 (:pr-count features)))
      (is (:has-dependencies? features))
      (is (contains? (:keywords features) :refactoring))
      (is (contains? (:keywords features) :stratified-design))
      (is (contains? (:keywords features) :multi-phase))
      (is (= :large (:size features)))
      (is (contains? (:constraint-mentions features) :rule-720))))

  (testing "analyze-spec extracts features from bugfix"
    (let [features (ws/analyze-spec bugfix-spec)]
      (is (= :bugfix (:type features)))
      (is (nil? (:pr-count features)))
      (is (contains? (:keywords features) :bugfix))))

  (testing "analyze-spec extracts features from docs"
    (let [features (ws/analyze-spec docs-spec)]
      (is (= :docs (:type features)))
      (is (contains? (:keywords features) :docs-only))))

  (testing "analyze-spec extracts features from large feature"
    (let [features (ws/analyze-spec large-feature-spec)]
      (is (= :feature (:type features)))
      (is (contains? (:keywords features) :large-scope))))

  (testing "analyze-spec extracts features from unknown spec"
    (let [features (ws/analyze-spec unknown-spec)]
      (is (= :unknown (:type features)))
      (is (= :unknown (:size features))))))

;------------------------------------------------------------------------------ Layer 1 Tests
;; Rule matching

(deftest match-rule-test
  (testing "Multi-phase implementation rule matches"
    (let [features (ws/analyze-spec multi-phase-refactor-spec)
          result (ws/match-rule features)]
      (is (= :canonical-sdlc-v1 (:workflow-type result)))
      (is (= :comprehensive (:selection-profile result)))
      (is (= :high (:confidence result)))
      (is (string? (:reason result)))))

  (testing "Refactoring with stratification rule matches"
    (let [features (ws/analyze-spec refactor-with-rule-210-spec)
          result (ws/match-rule features)]
      (is (= :canonical-sdlc-v1 (:workflow-type result)))
      (is (= :comprehensive (:selection-profile result)))
      (is (= :high (:confidence result)))))

  (testing "Bug fix rule matches"
    (let [features (ws/analyze-spec bugfix-spec)
          result (ws/match-rule features)]
      (is (= :lean-sdlc-v1 (:workflow-type result)))
      (is (= :fast (:selection-profile result)))
      (is (= :high (:confidence result)))))

  (testing "Docs only rule matches"
    (let [features (ws/analyze-spec docs-spec)
          result (ws/match-rule features)]
      (is (= :lean-sdlc-v1 (:workflow-type result)))
      (is (= :fast (:selection-profile result)))
      (is (= :high (:confidence result)))))

  (testing "Large feature rule matches"
    (let [features (ws/analyze-spec large-feature-spec)
          result (ws/match-rule features)]
      (is (= :canonical-sdlc-v1 (:workflow-type result)))
      (is (= :comprehensive (:selection-profile result)))
      (is (= :medium (:confidence result)))))

  (testing "Unknown defaults to lean-sdlc-v1"
    (let [features (ws/analyze-spec unknown-spec)
          result (ws/match-rule features)]
      (is (= :lean-sdlc-v1 (:workflow-type result)))
      (is (= :default (:selection-profile result)))
      (is (= :low (:confidence result))))))

;------------------------------------------------------------------------------ Layer 2 Tests
;; Workflow selection with reasoning

(deftest select-workflow-test
  (testing "select-workflow returns complete result for multi-phase refactor"
    (let [result (ws/select-workflow multi-phase-refactor-spec)]
      (is (= :canonical-sdlc-v1 (:workflow-type result)))
      (is (= :comprehensive (:selection-profile result)))
      (is (= :high (:confidence result)))
      (is (string? (:reason result)))
      (is (map? (:features result)))
      (is (= 6 (get-in result [:features :pr-count])))))

  (testing "select-workflow handles bugfix"
    (let [result (ws/select-workflow bugfix-spec)]
      (is (= :lean-sdlc-v1 (:workflow-type result)))
      (is (= :fast (:selection-profile result)))
      (is (= :high (:confidence result)))))

  (testing "select-workflow handles docs-only"
    (let [result (ws/select-workflow docs-spec)]
      (is (= :lean-sdlc-v1 (:workflow-type result)))
      (is (= :fast (:selection-profile result)))
      (is (= :high (:confidence result)))))

  (testing "select-workflow handles large feature"
    (let [result (ws/select-workflow large-feature-spec)]
      (is (= :canonical-sdlc-v1 (:workflow-type result)))
      (is (= :comprehensive (:selection-profile result)))
      (is (= :medium (:confidence result)))))

  (testing "select-workflow handles unknown with safe default"
    (let [result (ws/select-workflow unknown-spec)]
      (is (= :lean-sdlc-v1 (:workflow-type result)))
      (is (= :default (:selection-profile result)))
      (is (= :low (:confidence result))))))

(deftest explain-selection-test
  (testing "explain-selection generates user-facing explanation"
    (let [reason (messages/t :selector/reason-multi-phase {:pr-count 6})
          selection {:workflow-type :canonical-sdlc-v1
                     :confidence :high
                     :reason reason}
          explanation (ws/explain-selection selection)]
      (is (string? explanation))
      (is (.contains explanation "canonical-sdlc-v1"))
      (is (.contains explanation reason))
      (is (.contains explanation (messages/t :selector/override-hint)))))

  (testing "explain-selection shows confidence markers"
    (let [high-conf (ws/explain-selection {:workflow-type :canonical-sdlc-v1
                                           :confidence :high
                                           :reason "Test"})
          medium-conf (ws/explain-selection {:workflow-type :lean-sdlc-v1
                                             :confidence :medium
                                             :reason "Test"})
          low-conf (ws/explain-selection {:workflow-type :lean-sdlc-v1
                                          :confidence :low
                                          :reason "Test"})]
      (is (not (.contains high-conf "confidence")))
      (is (.contains medium-conf (messages/t :selector/confidence-medium)))
      (is (.contains low-conf (messages/t :selector/confidence-low))))))

;------------------------------------------------------------------------------ Integration Tests
;; End-to-end workflow selection

(deftest workflow-selection-integration-test
  (testing "Emojui-style multi-phase refactor selects canonical-sdlc-v1"
    (let [result (ws/select-workflow multi-phase-refactor-spec)]
      (is (= :canonical-sdlc-v1 (:workflow-type result)))
      (is (= :high (:confidence result)))))

  (testing "Bug fix selects lean-sdlc-v1"
    (let [result (ws/select-workflow bugfix-spec)]
      (is (= :lean-sdlc-v1 (:workflow-type result)))
      (is (= :high (:confidence result)))))

  (testing "Explicit workflow-type should be checked by caller first"
    ;; The selector doesn't check :spec/workflow-type - that's the caller's job
    (is (= :simple-test-v1 (:spec/workflow-type explicit-workflow-spec)))
    ;; If we ignore the explicit type and run selector, it would pick something else
    (let [result (ws/select-workflow (dissoc explicit-workflow-spec :spec/workflow-type))]
      (is (not= :simple-test-v1 (:workflow-type result))))))

;------------------------------------------------------------------------------ Edge Cases

(deftest edge-cases-test
  (testing "Empty spec defaults to lean-sdlc-v1"
    (let [result (ws/select-workflow {})]
      (is (= :lean-sdlc-v1 (:workflow-type result)))
      (is (= :low (:confidence result)))))

  (testing "Spec with only title defaults to lean-sdlc-v1"
    (let [result (ws/select-workflow {:spec/title "Test"})]
      (is (= :lean-sdlc-v1 (:workflow-type result)))
      (is (= :low (:confidence result)))))

  (testing "Refactor keyword in title triggers refactoring detection"
    (let [result (ws/select-workflow
                  {:spec/title "Refactor authentication"
                   :spec/description "Large refactoring with many phases"
                   :spec/raw-data
                   {:implementation-plan
                    {:pr-1 {:branch "a"}
                     :pr-2 {:branch "b"}
                     :pr-3 {:branch "c"}
                     :pr-4 {:branch "d"}
                     :pr-5 {:branch "e"}}}})]
      (is (= :canonical-sdlc-v1 (:workflow-type result)))
      (is (= :high (:confidence result)))))

  (testing "Small feature with 'simple' keyword stays lean"
    (let [result (ws/select-workflow small-feature-spec)]
      ;; Even though it's a feature, the small size and 'simple' keyword
      ;; should prevent it from being canonical
      (is (not= :canonical-sdlc-v1 (:workflow-type result))))))
