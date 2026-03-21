(ns ai.miniforge.dogfood-test
  "Dogfooding tests - using miniforge to improve miniforge.

   These tests demonstrate miniforge's meta-loop capability:
   - The outer loop executes workflows
   - The meta-loop (operator) observes, proposes improvements
   - The meta-meta loop (this session) only intervenes when needed

   Tests marked ^:dogfood require real LLM access and have higher budgets.
   Run with: clojure -M:dev:test -e \"(require 'ai.miniforge.dogfood-test) (clojure.test/run-tests 'ai.miniforge.dogfood-test)\""
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.string]
   [ai.miniforge.test-harness :as harness]
   [ai.miniforge.llm.interface :as llm]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (harness/cleanup-test-state!)))))

;; ============================================================================
;; Dogfooding Scenarios
;; ============================================================================

(def dogfood-specs
  "Test specifications for dogfooding."

  {:add-docstring
   {:title "Add docstring to function"
    :description "Add a comprehensive docstring to this function that explains
                  what it does, its arguments, and return value:

                  (defn clamp [x min-val max-val]
                    (max min-val (min max-val x)))"}

   :create-test
   {:title "Create tests for function"
    :description "Create comprehensive clojure.test tests for this function:

                  (defn snake->kebab [s]
                    (when s
                      (clojure.string/replace s \"_\" \"-\")))

                  Include tests for: normal input, empty string, nil input, multiple underscores."}

   :fix-bug
   {:title "Fix nil handling bug"
    :description "This function throws NullPointerException on nil input. Fix it to handle nil gracefully:

                  (defn format-name [m]
                    (str (:first m) \" \" (:last m)))

                  Return empty string for nil input."}

   :improve-function
   {:title "Improve function efficiency"
    :description "Improve this function to be more idiomatic Clojure using threading macros:

                  (defn process-items [items]
                    (filter identity
                      (map (fn [x] (when (pos? (:value x)) x))
                        (sort-by :priority items))))"}})

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn extract-code
  "Extract code from LLM response, handling markdown code blocks."
  [content]
  (when content
    (if-let [match (re-find #"```(?:clojure)?\n([\s\S]*?)```" content)]
      (second match)
      content)))

(defn valid-clojure?
  "Check if string is valid Clojure code."
  [code]
  (when code
    (try
      (let [_parsed (read-string code)]
        true)
      (catch Exception _ false))))

;; ============================================================================
;; Mock Tests (Fast, deterministic)
;; ============================================================================

(deftest mock-dogfood-workflow-test
  (testing "Dogfood workflow structure with mock LLM"
    (let [mock-plan (str "## Implementation Plan\n\n"
                         "1. Add docstring to the function\n"
                         "```json\n"
                         "[{\"title\": \"Add docstring\", \"type\": \"implement\"}]\n"
                         "```")
          mock-impl (str "Here is the improved function:\n\n"
                         "```clojure\n"
                         "(defn clamp\n"
                         "  \"Clamp a value to be within [min-val, max-val].\n"
                         "  Returns min-val if x < min-val, max-val if x > max-val,\n"
                         "  otherwise returns x.\"\n"
                         "  [x min-val max-val]\n"
                         "  (max min-val (min max-val x)))\n"
                         "```")
          env (harness/create-mock-orchestrator {:outputs [mock-plan mock-impl]})]

      ;; Verify orchestrator was created
      (is (some? (:orchestrator env)))

      ;; Execute workflow
      (let [result (harness/execute-workflow env (:add-docstring dogfood-specs))]
        (is (keyword? (:status result))
            "Result should have a status")))))

(deftest code-extraction-test
  (testing "Extracts code from markdown"
    (let [content "Here is the code:\n```clojure\n(defn foo [] 42)\n```"
          code (extract-code content)]
      (is (= "(defn foo [] 42)" (clojure.string/trim code)))))

  (testing "Returns content if no markdown"
    (let [content "(defn foo [] 42)"
          code (extract-code content)]
      (is (= "(defn foo [] 42)" code))))

  (testing "Handles nil"
    (is (nil? (extract-code nil)))))

(deftest clojure-validation-test
  (testing "Valid Clojure is recognized"
    (is (valid-clojure? "(defn foo [] 42)")))

  (testing "Invalid Clojure is rejected"
    (is (not (valid-clojure? "(defn foo [")))))

;; ============================================================================
;; Real LLM Tests (Requires Claude CLI)
;; ============================================================================

(deftest ^:dogfood ^:integration real-docstring-addition-test
  (testing "Add docstring to real function using Claude CLI"
    (when (harness/cli-available? :claude)
      (let [client (llm/create-client {:backend :claude})
            prompt (str "Add a comprehensive docstring to this Clojure function. "
                        "Return ONLY the improved function with the docstring, no explanations:\n\n"
                        "(defn clamp [x min-val max-val]\n"
                        "  (max min-val (min max-val x)))")
            response (llm/chat client prompt
                               {:system "You are a Clojure expert. Return only valid Clojure code."})]
        (is (llm/success? response)
            "LLM request should succeed")
        (when (llm/success? response)
          (let [code (extract-code (llm/get-content response))]
            (is (valid-clojure? code)
                "Generated code should be valid Clojure")
            (is (re-find #"\"[^\"]+\"" code)
                "Code should contain a docstring")))))))

(deftest ^:dogfood ^:integration real-test-generation-test
  (testing "Generate tests for function using Claude CLI"
    (when (harness/cli-available? :claude)
      (let [client (llm/create-client {:backend :claude})
            prompt (str "Write clojure.test tests for this function. "
                        "Include tests for: normal input, empty string, nil input, edge cases. "
                        "Return ONLY the test code, no explanations:\n\n"
                        "(defn snake->kebab [s]\n"
                        "  (when s\n"
                        "    (clojure.string/replace s \"_\" \"-\")))")
            response (llm/chat client prompt
                               {:system "You are a Clojure testing expert. Return only valid Clojure test code."})]
        (is (llm/success? response)
            "LLM request should succeed")
        (when (llm/success? response)
          (let [code (extract-code (llm/get-content response))]
            (is (valid-clojure? code)
                "Generated test code should be valid Clojure")
            (is (re-find #"deftest" code)
                "Code should contain deftest")
            (is (re-find #"is\s*\(" code)
                "Code should contain test assertions")))))))

(deftest ^:dogfood ^:integration real-bug-fix-test
  (testing "Fix nil-handling bug using Claude CLI"
    (when (harness/cli-available? :claude)
      (let [client (llm/create-client {:backend :claude})
            prompt (str "Fix this function to handle nil input gracefully "
                        "(return empty string for nil). "
                        "Return ONLY the fixed function, no explanations:\n\n"
                        "(defn format-name [m]\n"
                        "  (str (:first m) \" \" (:last m)))")
            response (llm/chat client prompt
                               {:system "You are a Clojure expert. Return only valid Clojure code."})]
        (is (llm/success? response)
            "LLM request should succeed")
        (when (llm/success? response)
          (let [code (extract-code (llm/get-content response))]
            (is (valid-clojure? code)
                "Generated code should be valid Clojure")
            ;; The fix should include nil handling
            (is (or (re-find #"when" code)
                    (re-find #"if" code)
                    (re-find #"nil\?" code)
                    (re-find #"some\?" code)
                    (re-find #"fnil" code)
                    (re-find #"\(or" code))
                "Code should include nil handling")))))))

;; ============================================================================
;; Self-Healing Test (Meta-loop validation)
;; ============================================================================

(deftest ^:dogfood self-healing-mock-test
  (testing "Self-healing workflow with mock responses"
    ;; Simulate a workflow that fails, then succeeds after self-healing
    (let [;; First attempt fails with bad code
          bad-response "(defn broken ["
          ;; After self-healing, second attempt succeeds
          good-response "(defn fixed [x] (* x 2))"
          env (harness/create-mock-orchestrator {:outputs [bad-response good-response]})]

      ;; First call should return invalid code
      (let [client (:llm-client env)
            r1 (llm/chat client "generate code")]
        (is (not (valid-clojure? (llm/get-content r1)))
            "First response should be invalid"))

      ;; Second call (after hypothetical self-healing) returns valid code
      (let [client (:llm-client env)
            r2 (llm/chat client "generate code again")]
        (is (valid-clojure? (llm/get-content r2))
            "Second response should be valid after self-healing")))))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Run all mock tests (fast)
  (clojure.test/run-tests 'ai.miniforge.dogfood-test)

  ;; Run just the mock tests
  (clojure.test/test-vars [#'ai.miniforge.dogfood-test/mock-dogfood-workflow-test
                           #'ai.miniforge.dogfood-test/code-extraction-test
                           #'ai.miniforge.dogfood-test/clojure-validation-test])

  ;; Run a single real LLM test (costs money)
  (clojure.test/test-vars [#'ai.miniforge.dogfood-test/real-docstring-addition-test])

  ;; Quick manual test
  (let [client (llm/create-client {:backend :claude})]
    (llm/chat client "Write a Clojure function that doubles a number"
              {:system "Return only valid Clojure code."}))

  :end)
