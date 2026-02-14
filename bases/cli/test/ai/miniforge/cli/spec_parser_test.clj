(ns ai.miniforge.cli.spec-parser-test
  "Tests for spec parser normalization across all three input formats."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.spec-parser :as sp]))

;------------------------------------------------------------------------------ Layer 0 Tests
;; normalize-spec (via parse-spec-file internals)

;; We test normalize-spec indirectly through the public validate-spec,
;; and directly by calling the private normalize-spec via the var.

(def ^:private normalize-spec @#'sp/normalize-spec)

;------------------------------------------------------------------------------ Test Data

(def canonical-spec
  "Spec using canonical :spec/* format"
  {:spec/title "Refactor logging"
   :spec/description "Extract logging to separate module"
   :spec/intent {:type :refactor :scope ["src/logging"]}
   :spec/constraints ["no-breaking-changes"]
   :spec/acceptance-criteria ["tests pass" "coverage maintained"]
   :spec/tags [:refactoring]
   :workflow/type :full-sdlc
   :workflow/version "2.0.0"
   :spec/repo-url "https://github.com/example/repo"
   :spec/branch "feat/logging"
   :spec/llm-backend :anthropic
   :spec/sandbox true})

(def transitional-spec
  "Spec using transitional :task/* format"
  {:task/title "Refactor logging"
   :task/description "Extract logging to separate module"
   :task/intent {:type :refactor :scope ["src/logging"]}
   :task/constraints ["no-breaking-changes"]
   :task/acceptance-criteria ["tests pass" "coverage maintained"]
   :task/code-artifact {:code/id "abc" :code/files []}
   :workflow/type :full-sdlc
   :workflow/version "2.0.0"})

(def legacy-spec
  "Spec using legacy unnamespaced format"
  {:title "Refactor logging"
   :description "Extract logging to separate module"
   :intent {:type :refactor :scope ["src/logging"]}
   :constraints ["no-breaking-changes"]
   :acceptance-criteria ["tests pass"]
   :tags [:refactoring]
   :workflow/type :full-sdlc
   :repo-url "https://github.com/example/repo"
   :branch "feat/logging"
   :sandbox true})

(def plan-spec
  "Spec with :plan/tasks"
  {:spec/title "Multi-phase feature"
   :spec/description "Build feature in phases"
   :plan/tasks [{:task/id :step-1 :task/description "First step" :task/type :implement}
                {:task/id :step-2 :task/description "Second step" :task/dependencies [:step-1]}]
   :workflow/type :canonical-sdlc})

;------------------------------------------------------------------------------ Normalization Tests

(deftest normalize-canonical-format-test
  (testing "canonical :spec/* format passes through correctly"
    (let [result (normalize-spec canonical-spec)]
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
      (is (= canonical-spec (:spec/raw-data result))))))

(deftest normalize-transitional-format-test
  (testing ":task/* format translates to :spec/*"
    (let [result (normalize-spec transitional-spec)]
      (is (= "Refactor logging" (:spec/title result)))
      (is (= "Extract logging to separate module" (:spec/description result)))
      (is (= {:type :refactor :scope ["src/logging"]} (:spec/intent result)))
      (is (= ["no-breaking-changes"] (:spec/constraints result)))
      (is (= ["tests pass" "coverage maintained"] (:spec/acceptance-criteria result)))
      (is (= {:code/id "abc" :code/files []} (:spec/code-artifact result)))
      (is (= :full-sdlc (:spec/workflow-type result)))
      (is (= transitional-spec (:spec/raw-data result))))))

(deftest normalize-legacy-format-test
  (testing "unnamespaced format translates to :spec/*"
    (let [result (normalize-spec legacy-spec)]
      (is (= "Refactor logging" (:spec/title result)))
      (is (= "Extract logging to separate module" (:spec/description result)))
      (is (= {:type :refactor :scope ["src/logging"]} (:spec/intent result)))
      (is (= ["no-breaking-changes"] (:spec/constraints result)))
      (is (= ["tests pass"] (:spec/acceptance-criteria result)))
      (is (= [:refactoring] (:spec/tags result)))
      (is (= :full-sdlc (:spec/workflow-type result)))
      (is (= "https://github.com/example/repo" (:spec/repo-url result)))
      (is (= "feat/logging" (:spec/branch result)))
      (is (true? (:spec/sandbox result)))
      (is (= legacy-spec (:spec/raw-data result))))))

(deftest normalize-plan-tasks-test
  (testing ":plan/tasks promoted to :spec/plan-tasks"
    (let [result (normalize-spec plan-spec)]
      (is (= 2 (count (:spec/plan-tasks result))))
      (is (= :step-1 (:task/id (first (:spec/plan-tasks result))))))))

;------------------------------------------------------------------------------ Priority Tests

(deftest priority-ordering-test
  (testing ":spec/* takes priority over :task/* and unnamespaced"
    (let [spec {:spec/title "Spec Title"
                :task/title "Task Title"
                :title "Plain Title"
                :spec/description "Spec Desc"
                :task/description "Task Desc"
                :description "Plain Desc"}
          result (normalize-spec spec)]
      (is (= "Spec Title" (:spec/title result)))
      (is (= "Spec Desc" (:spec/description result)))))

  (testing ":task/* takes priority over unnamespaced"
    (let [spec {:task/title "Task Title"
                :title "Plain Title"
                :task/description "Task Desc"
                :description "Plain Desc"}
          result (normalize-spec spec)]
      (is (= "Task Title" (:spec/title result)))
      (is (= "Task Desc" (:spec/description result))))))

;------------------------------------------------------------------------------ Validation Tests

(deftest validation-test
  (testing "spec must be a map"
    (is (thrown-with-msg? Exception #"must be a map"
          (normalize-spec "not a map"))))

  (testing "spec must have a title"
    (is (thrown-with-msg? Exception #"must have a title"
          (normalize-spec {:description "No title"}))))

  (testing "spec must have a description"
    (is (thrown-with-msg? Exception #"must have a description"
          (normalize-spec {:title "No desc"})))))

;------------------------------------------------------------------------------ Defaults Tests

(deftest defaults-test
  (testing "intent defaults to {:type :general}"
    (let [result (normalize-spec {:spec/title "T" :spec/description "D"})]
      (is (= {:type :general} (:spec/intent result)))))

  (testing "constraints default to empty vector"
    (let [result (normalize-spec {:spec/title "T" :spec/description "D"})]
      (is (= [] (:spec/constraints result)))))

  (testing "tags default to empty vector"
    (let [result (normalize-spec {:spec/title "T" :spec/description "D"})]
      (is (= [] (:spec/tags result)))))

  (testing "optional keys absent when not provided"
    (let [result (normalize-spec {:spec/title "T" :spec/description "D"})]
      (is (nil? (:spec/acceptance-criteria result)))
      (is (nil? (:spec/code-artifact result)))
      (is (nil? (:spec/repo-url result)))
      (is (nil? (:spec/branch result)))
      (is (nil? (:spec/llm-backend result)))
      (is (nil? (:spec/sandbox result)))
      (is (nil? (:spec/plan-tasks result))))))

;------------------------------------------------------------------------------ validate-spec Tests

(deftest validate-spec-test
  (testing "valid spec passes validation"
    (let [result (sp/validate-spec {:spec/title "T" :spec/description "D" :spec/intent {:type :feature}})]
      (is (true? (:valid? result)))))

  (testing "missing title fails validation"
    (let [result (sp/validate-spec {:spec/description "D"})]
      (is (false? (:valid? result)))
      (is (some #(.contains % ":title") (:errors result)))))

  (testing "missing description fails validation"
    (let [result (sp/validate-spec {:spec/title "T"})]
      (is (false? (:valid? result)))
      (is (some #(.contains % ":description") (:errors result)))))

  (testing "intent without :type fails validation"
    (let [result (sp/validate-spec {:spec/title "T" :spec/description "D" :spec/intent {:scope ["x"]}})]
      (is (false? (:valid? result)))
      (is (some #(.contains % "Intent") (:errors result))))))
