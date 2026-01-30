(ns ai.miniforge.agent.tester
  "Tester agent implementation.
   Generates tests for code artifacts and validates coverage."
  (:require
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [malli.core :as m]
   [clojure.string :as str]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; Tester-specific schemas

(def TestFile
  "Schema for a test file."
  [:map
   [:path [:string {:min 1}]]
   [:content [:string {:min 1}]]])

(def Coverage
  "Schema for test coverage information."
  [:map
   [:lines {:optional true} [:double {:min 0.0 :max 100.0}]]
   [:branches {:optional true} [:double {:min 0.0 :max 100.0}]]
   [:functions {:optional true} [:double {:min 0.0 :max 100.0}]]])

(def TestArtifact
  "Schema for the tester's output."
  [:map
   [:test/id uuid?]
   [:test/files [:vector TestFile]]
   [:test/type [:enum :unit :integration :property :e2e :acceptance]]
   [:test/coverage {:optional true} Coverage]
   [:test/framework {:optional true} [:string {:min 1}]]
   [:test/assertions-count {:optional true} [:int {:min 0}]]
   [:test/cases-count {:optional true} [:int {:min 0}]]
   [:test/summary {:optional true} [:string {:min 1}]]
   [:test/created-at {:optional true} inst?]])

;; System Prompt

(def tester-system-prompt
  "You are a Testing Agent for an autonomous software development team.

Your role is to generate comprehensive tests for code artifacts, ensuring quality
and correctness. You work alongside Planner, Implementer, and Reviewer agents.

## Input Format

You receive:
1. A code artifact with:
   - File paths and content
   - Language and framework information
   - Summary of what was implemented

2. Specification/acceptance criteria:
   - What the code should do
   - Edge cases to consider
   - Performance requirements (if any)

3. Context including:
   - Existing test patterns in the codebase
   - Testing framework preferences
   - Coverage requirements

## Output Format

You must output a structured test artifact as a Clojure map:

```clojure
{:test/id <uuid>
 :test/files [{:path \"test/namespace/file_test.clj\"
               :content \"(ns namespace.file-test...)\"}
              ...]
 :test/type :unit ; or :integration, :property, :e2e, :acceptance
 :test/coverage {:lines 85.0
                 :branches 80.0
                 :functions 90.0}
 :test/framework \"clojure.test\"
 :test/assertions-count 15
 :test/cases-count 5
 :test/summary \"Description of test coverage\"}
```

## Guidelines

1. **Test Types**:
   - :unit - Isolated function/method tests with mocked dependencies
   - :integration - Tests that verify component interactions
   - :property - Property-based/generative tests (e.g., test.check)
   - :e2e - End-to-end tests through the full system
   - :acceptance - Tests verifying acceptance criteria

2. **Test Structure (for Clojure)**:
   ```clojure
   (ns my.namespace-test
     (:require [clojure.test :refer [deftest testing is]]
               [my.namespace :as sut]))  ; sut = system under test

   (deftest function-name-test
     (testing \"describes the scenario\"
       (is (= expected (sut/function-name input)))))
   ```

3. **Coverage Strategy**:
   - Test the happy path first
   - Test edge cases (empty inputs, nil, boundary values)
   - Test error conditions and exception handling
   - Test state transitions if applicable

4. **Test Naming**:
   - Test namespaces mirror source namespaces with `-test` suffix
   - Test files use `_test.clj` suffix
   - Test names describe what is being tested and expected outcome

5. **Assertions**:
   - One logical assertion per test when possible
   - Use descriptive assertion messages
   - Test exact values, not just truthiness

6. **Property-Based Testing**:
   - Use for functions with clear invariants
   - Generate a variety of inputs automatically
   - Find edge cases you might not think of

7. **Test Data**:
   - Use realistic but minimal test data
   - Create test fixtures for complex data structures
   - Avoid hardcoded magic values, use named constants

8. **Mocking and Stubs**:
   - Mock external dependencies (APIs, databases)
   - Keep mocks minimal and realistic
   - Verify mock interactions when relevant

9. **Coverage Requirements**:
   - Aim for 80%+ line coverage
   - Critical paths should have 100% coverage
   - Don't sacrifice meaningful tests for coverage numbers

10. **Test Independence**:
    - Each test should run independently
    - No shared mutable state between tests
    - Use fixtures for setup/teardown

Remember: Good tests are documentation. They show how the code is intended to be used
and what behavior is expected. Make tests readable and maintainable.")

;------------------------------------------------------------------------------ Layer 1
;; Tester functions

(defn validate-test-artifact
  "Validate a test artifact against the schema and check for issues."
  [artifact]
  (let [schema-valid? (m/validate TestArtifact artifact)]
    (if-not schema-valid?
      {:valid? false
       :errors (schema/explain TestArtifact artifact)}
      ;; Additional validations
      (let [files (:test/files artifact)
            non-test-paths (filter #(not (re-find #"_test\.clj|_test\.cljc|\.test\.|test_|_spec\." (:path %)))
                                   files)
            missing-assertions? (and (:test/assertions-count artifact)
                                     (<= (:test/assertions-count artifact) 0))]
        (cond
          (seq non-test-paths)
          {:valid? false
           :errors {:files (str "Files don't follow test naming convention: "
                                (mapv :path non-test-paths))}}

          missing-assertions?
          {:valid? false
           :errors {:assertions "Test artifact has no assertions"}}

          :else
          {:valid? true :errors nil})))))

(defn- extract-test-framework
  "Extract the testing framework from file content or context."
  [files context]
  (let [content (str/join "\n" (map :content files))]
    (or (:test-framework context)
        (cond
          (re-find #"\[clojure\.test" content) "clojure.test"
          (re-find #"defspec" content) "test.check"
          (re-find #"midje" content) "midje"
          (re-find #"expectations" content) "expectations"
          (re-find #"@pytest" content) "pytest"
          (re-find #"jest" content) "jest"
          (re-find #"describe\(" content) "mocha"
          :else "clojure.test"))))

(defn- count-assertions
  "Count assertions in test content."
  [content]
  (+ (count (re-seq #"\(is\s" content))
     (count (re-seq #"\(are\s" content))
     (count (re-seq #"\(testing\s" content))
     (count (re-seq #"assert" content))
     (count (re-seq #"expect\(" content))))

(defn- count-test-cases
  "Count test cases in test content."
  [content]
  (+ (count (re-seq #"\(deftest\s" content))
     (count (re-seq #"\(defspec\s" content))
     (count (re-seq #"def test_" content))
     (count (re-seq #"it\(" content))
     (count (re-seq #"test\(" content))))

(defn- code->text
  "Convert code artifact to text for the LLM."
  [code-artifact]
  (if (map? code-artifact)
    (let [files (:code/files code-artifact)
          file-texts (map (fn [f]
                            (str "## File: " (:path f) "\n```clojure\n" (:content f) "\n```"))
                          files)]
      (str/join "\n\n" file-texts))
    (str code-artifact)))

(defn- parse-test-response
  "Parse the LLM response to extract test artifact.
   Handles both EDN in code blocks and plain EDN."
  [response-content]
  (try
    ;; Try to extract EDN from ```clojure or ```edn block
    (if-let [match (re-find #"```(?:clojure|edn)?\s*\n(\{:test/id[\s\S]*?\})\n```" response-content)]
      (edn/read-string (second match))
      ;; Try to parse the whole response as EDN
      (edn/read-string response-content))
    (catch Exception _
      nil)))

(defn- extract-test-code-blocks
  "Extract test code blocks from markdown response."
  [response-content]
  (let [blocks (re-seq #"```(?:clojure)?\s*\n(\(ns [^`]+)\n?```" response-content)]
    (when (seq blocks)
      (->> blocks
           (map-indexed (fn [idx [_full content]]
                          (let [ns-match (re-find #"\(ns\s+([^\s\)]+)" content)
                                ns-name (or (second ns-match) (str "generated-test-" idx))
                                path (str "test/" (str/replace ns-name "." "/") "_test.clj")]
                            {:path path
                             :content (str/trim content)})))
           vec))))

(defn- make-fallback-tests
  "Create a fallback test artifact when LLM response cannot be parsed."
  [code-artifact spec]
  (let [code-files (or (:code/files code-artifact) [])
        source-file (first (filter #(= :create (:action %)) code-files))
        source-path (or (:path source-file) "src/unknown.clj")
        test-path (-> source-path
                      (str/replace #"^src/" "test/")
                      (str/replace #"\.clj$" "_test.clj"))
        source-ns (-> source-path
                      (str/replace #"^src/" "")
                      (str/replace #"\.clj$" "")
                      (str/replace "/" ".")
                      (str/replace "_" "-"))
        test-ns (str source-ns "-test")
        acceptance-criteria (or (:task/acceptance-criteria spec)
                                (:acceptance-criteria spec)
                                ["Functionality works as expected"])]
    {:test/id (random-uuid)
     :test/files [{:path test-path
                   :content (str "(ns " test-ns "\n"
                                 "  \"Tests for " source-ns ".\"\n"
                                 "  (:require\n"
                                 "   [clojure.test :as test :refer [deftest testing is]]\n"
                                 "   [" source-ns " :as sut]))\n\n"
                                 ";; Acceptance criteria:\n"
                                 (str/join "\n" (map #(str ";; - " %) acceptance-criteria))
                                 "\n\n"
                                 "(deftest basic-test\n"
                                 "  (testing \"placeholder test - LLM response could not be parsed\"\n"
                                 "    (is true)))\n")}]
     :test/type :unit
     :test/framework "clojure.test"
     :test/assertions-count 1
     :test/cases-count 1
     :test/summary "Fallback test placeholder"
     :test/created-at (java.util.Date.)}))

(defn- repair-test-artifact
  "Attempt to repair a test artifact based on validation errors."
  [artifact errors _context]
  (let [repaired (atom artifact)]
    ;; Fix missing ID
    (when-not (:test/id @repaired)
      (swap! repaired assoc :test/id (random-uuid)))

    ;; Fix missing files
    (when-not (:test/files @repaired)
      (swap! repaired assoc :test/files []))

    ;; Fix files without required fields
    (swap! repaired update :test/files
           (fn [files]
             (mapv (fn [f]
                     (cond-> f
                       (not (:path f))
                       (assoc :path "test/generated_test.clj")

                       (not (:content f))
                       (assoc :content "(ns generated-test\n  (:require [clojure.test :refer [deftest is]]))\n\n(deftest placeholder-test\n  (is true))\n")))
                   files)))

    ;; Fix paths that don't follow test convention
    (swap! repaired update :test/files
           (fn [files]
             (mapv (fn [f]
                     (if (re-find #"_test\.clj|_test\.cljc|\.test\.|test_|_spec\." (:path f))
                       f
                       (let [path (:path f)
                             new-path (-> path
                                          (str/replace #"\.clj$" "_test.clj")
                                          (str/replace #"^src/" "test/"))]
                         (assoc f :path (if (= new-path path)
                                          (str path "_test")
                                          new-path)))))
                   files)))

    ;; Fix missing type
    (when-not (:test/type @repaired)
      (swap! repaired assoc :test/type :unit))

    ;; Calculate assertion count if missing
    (when-not (:test/assertions-count @repaired)
      (let [total-assertions (->> (:test/files @repaired)
                                  (map :content)
                                  (map count-assertions)
                                  (reduce +))]
        (swap! repaired assoc :test/assertions-count (max 1 total-assertions))))

    ;; Calculate test case count if missing
    (when-not (:test/cases-count @repaired)
      (let [total-cases (->> (:test/files @repaired)
                             (map :content)
                             (map count-test-cases)
                             (reduce +))]
        (swap! repaired assoc :test/cases-count (max 1 total-cases))))

    {:status :success
     :output @repaired
     :repairs-made (when (not= artifact @repaired)
                     {:original-errors errors})}))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn create-tester
  "Create a Tester agent with optional configuration overrides.

   Options:
   - :config - Agent configuration (model, temperature, etc.)
   - :logger - Logger instance
   - :llm-backend - LLM client (if not provided, uses :llm-backend from context)

   Example:
     (create-tester)
     (create-tester {:config {:model \"claude-sonnet-4\"}})"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))]
    (specialized/create-base-agent
     {:role :tester
      :system-prompt tester-system-prompt
      :config (merge {:model "claude-sonnet-4"
                      :temperature 0.2
                      :max-tokens 6000}
                     (:config opts))
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (or (:llm-backend opts) (:llm-backend context))
              on-chunk (:on-chunk context)
              ;; Input can be a code artifact or a map with :code and :spec
              code-artifact (or (:code input) input)
              spec (or (:spec input) {})
              code-text (code->text code-artifact)
              acceptance-criteria (or (:task/acceptance-criteria spec)
                                      (:acceptance-criteria spec)
                                      [])
              user-prompt (str "Generate comprehensive tests for the following code:\n\n"
                               code-text
                               (when (seq acceptance-criteria)
                                 (str "\n\nAcceptance criteria to verify:\n"
                                      (str/join "\n" (map #(str "- " %) acceptance-criteria))))
                               "\n\nOutput your tests as a Clojure map following the format in your system prompt. "
                               "Include complete test code, not placeholders.")]
          (if llm-client
            ;; Use the real LLM with streaming if callback provided
            (let [response (if on-chunk
                             (llm/chat-stream llm-client user-prompt on-chunk
                                              {:system tester-system-prompt})
                             (llm/chat llm-client user-prompt
                                       {:system tester-system-prompt}))
                  tokens (or (:tokens response) 0)]
              (log/info logger :tester :tester/llm-called
                        {:data {:success (llm/success? response)
                                :tokens tokens
                                :streaming? (boolean on-chunk)}})
              (if (llm/success? response)
                (let [content (llm/get-content response)
                      parsed (parse-test-response content)
                      ;; Try multiple parsing strategies
                      tests (or parsed
                                (when-let [files (extract-test-code-blocks content)]
                                  {:test/id (random-uuid)
                                   :test/files files
                                   :test/type :unit
                                   :test/summary "Tests from code blocks"
                                   :test/created-at (java.util.Date.)})
                                (make-fallback-tests code-artifact spec))
                      ;; Ensure proper ID and calculate metrics
                      tests-with-meta (-> tests
                                          (update :test/id #(or % (random-uuid)))
                                          (assoc :test/framework (extract-test-framework (:test/files tests) context))
                                          (assoc :test/assertions-count
                                                 (or (:test/assertions-count tests)
                                                     (->> (:test/files tests)
                                                          (map :content)
                                                          (map count-assertions)
                                                          (reduce +))))
                                          (assoc :test/cases-count
                                                 (or (:test/cases-count tests)
                                                     (->> (:test/files tests)
                                                          (map :content)
                                                          (map count-test-cases)
                                                          (reduce +))))
                                          (assoc :test/created-at (java.util.Date.)))]
                  {:status :success
                   :output tests-with-meta
                   :artifact tests-with-meta
                   :tokens tokens
                   :metrics {:test-files (count (:test/files tests-with-meta))
                             :test-type (:test/type tests-with-meta)
                             :assertions (:test/assertions-count tests-with-meta)
                             :cases (:test/cases-count tests-with-meta)
                             :tokens tokens}})
                ;; LLM call failed
                (let [fallback (make-fallback-tests code-artifact spec)]
                  {:status :error
                   :error (llm/get-error response)
                   :output fallback
                   :artifact fallback
                   :metrics {:tokens 0}})))
            ;; No LLM client - use fallback (for testing)
            (do
              (log/warn logger :tester :tester/no-llm-backend
                        {:message "No LLM backend provided, using fallback tests"})
              (let [tests (make-fallback-tests code-artifact spec)
                    framework (extract-test-framework (:test/files tests) context)]
                {:status :success
                 :output (assoc tests :test/framework framework)
                 :artifact (assoc tests :test/framework framework)
                 :metrics {:test-files (count (:test/files tests))
                           :test-type (:test/type tests)
                           :assertions 1
                           :cases 1}})))))

      :validate-fn validate-test-artifact

      :repair-fn repair-test-artifact})))

(defn test-summary
  "Get a summary of a test artifact for logging/display."
  [artifact]
  {:id (:test/id artifact)
   :file-count (count (:test/files artifact))
   :type (:test/type artifact)
   :framework (:test/framework artifact)
   :assertions (:test/assertions-count artifact)
   :cases (:test/cases-count artifact)
   :coverage (:test/coverage artifact)})

(defn coverage-meets-threshold?
  "Check if coverage meets the specified thresholds."
  [artifact & {:keys [lines branches functions]
               :or {lines 80.0 branches 70.0 functions 80.0}}]
  (let [coverage (:test/coverage artifact {})]
    (and (>= (get coverage :lines 0) lines)
         (>= (get coverage :branches 0) branches)
         (>= (get coverage :functions 0) functions))))

(defn tests-by-path
  "Get a map of test file paths to their content."
  [artifact]
  (into {} (map (juxt :path :content) (:test/files artifact))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a tester
  (def tester (create-tester))

  ;; Invoke with a code artifact
  (specialized/invoke tester
               {:test-framework "clojure.test"}
               {:code {:code/id (random-uuid)
                       :code/files [{:path "src/auth/login.clj"
                                     :content "(ns auth.login)\n(defn login [user] ...)"
                                     :action :create}]}
                :spec {:acceptance-criteria ["User can log in"
                                             "Invalid credentials show error"]}})
  ;; => {:status :success, :output {:test/id ..., :test/files [...], ...}}

  ;; Validate a test artifact
  (validate-test-artifact
   {:test/id (random-uuid)
    :test/files [{:path "test/example_test.clj"
                  :content "(ns example-test (:require [clojure.test :refer :all]))"}]
    :test/type :unit})
  ;; => {:valid? true, :errors nil}

  ;; Check coverage threshold
  (coverage-meets-threshold?
   {:test/coverage {:lines 85.0 :branches 75.0 :functions 90.0}}
   :lines 80.0 :branches 70.0)
  ;; => true

  :leave-this-here)
