(ns ai.miniforge.test-harness
  "Test harness for running integration tests with real LLM backends.

   This harness supports the meta-loop testing model:
   - Unit tests use mock LLM (fast, deterministic)
   - Integration tests use real LLM (validates actual behavior)
   - Dogfooding tests use miniforge to test/fix itself

   The meta-meta loop (human session) only intervenes when
   miniforge cannot self-correct through its operator/meta-loop."
  (:require
   [clojure.java.shell :as shell]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.orchestrator.interface :as orch]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.operator.interface :as operator]
   [babashka.fs :as fs]))

;; ============================================================================
;; Budget Configuration
;; ============================================================================

(def budgets
  "Budget tiers for different test types.
   Prevents runaway costs during testing."
  {:unit        {:max-tokens 1000   :max-cost-usd 0.01  :timeout-ms 30000}
   :integration {:max-tokens 10000  :max-cost-usd 0.10  :timeout-ms 120000}
   :e2e         {:max-tokens 50000  :max-cost-usd 0.50  :timeout-ms 300000}
   :dogfood     {:max-tokens 100000 :max-cost-usd 1.00  :timeout-ms 600000}})

;; ============================================================================
;; Test Infrastructure
;; ============================================================================

;; Global test state for cleanup tracking
(defonce ^:private test-state
  (atom {:stores []
         :temp-dirs #{}}))

(defn cleanup-test-state!
  "Clean up all test resources."
  []
  (doseq [{:keys [a-store]} (:stores @test-state)]
    (when a-store
      (try (artifact/close-store a-store) (catch Exception _))))
  (doseq [dir (:temp-dirs @test-state)]
    (when (fs/exists? dir)
      (fs/delete-tree dir)))
  (reset! test-state {:stores [] :temp-dirs #{}}))

(defn- register-store!
  "Register store for cleanup."
  [k-store a-store]
  (swap! test-state update :stores conj {:k-store k-store :a-store a-store}))

(defn- register-temp-dir!
  "Register temp directory for cleanup."
  [dir]
  (swap! test-state update :temp-dirs conj dir))

;; ============================================================================
;; Orchestrator Creation
;; ============================================================================

(defn create-mock-orchestrator
  "Create orchestrator with mock LLM for unit tests.

   Options:
   - :outputs - Vector of mock responses for sequential calls
   - :output  - Single mock response for all calls"
  [{:keys [outputs output] :or {output "Mock response"}}]
  (let [llm-client (llm/mock-client (if outputs {:outputs outputs} {:output output}))
        k-store (knowledge/create-store)
        a-store (artifact/create-store)]
    (register-store! k-store a-store)
    {:orchestrator (orch/create-orchestrator llm-client k-store a-store)
     :llm-client llm-client
     :knowledge-store k-store
     :artifact-store a-store}))

(defn create-real-orchestrator
  "Create orchestrator with real LLM backend for integration tests.

   Options:
   - :backend - CLI backend (:claude, :cursor) - default :claude
   - :budget  - Budget tier keyword or custom map
   - :logger  - Optional logger instance
   - :with-operator? - Include operator for meta-loop (default false)"
  [{:keys [backend budget logger with-operator?]
    :or {backend :claude
         budget :integration
         with-operator? false}}]
  (let [budget-config (if (keyword? budget) (get budgets budget) budget)
        llm-client (llm/create-client {:backend backend :logger logger})
        k-store (knowledge/create-store)
        ;; Use temp dir for artifact store to ensure isolation
        temp-dir (str (fs/create-temp-dir {:prefix "miniforge-test-"}))
        _ (register-temp-dir! temp-dir)
        a-store (artifact/create-store {:dir temp-dir})
        _ (register-store! k-store a-store)
        op (when with-operator? (operator/create-operator k-store))
        orchestrator (orch/create-orchestrator llm-client k-store a-store
                                                (cond-> {}
                                                  op (assoc :operator op)))]
    {:orchestrator orchestrator
     :llm-client llm-client
     :knowledge-store k-store
     :artifact-store a-store
     :operator op
     :budget budget-config
     :backend backend}))

;; ============================================================================
;; Test Execution Helpers
;; ============================================================================

(defn execute-workflow
  "Execute a workflow with the given spec.

   Returns:
   {:workflow-id uuid
    :status keyword
    :result map
    :duration-ms long
    :within-budget? boolean}"
  [{:keys [orchestrator budget]} spec]
  (let [start-time (System/currentTimeMillis)
        result (orch/execute-workflow orchestrator spec {:budget budget})
        duration (- (System/currentTimeMillis) start-time)]
    (assoc result
           :duration-ms duration
           :within-budget? (< duration (or (:timeout-ms budget) 300000)))))

(defn validate-result
  "Validate workflow result against a set of validators.

   Each validator is a function (fn [result] {:valid? bool :error string})

   Returns:
   {:all-valid? boolean
    :validations [{:validator-name string :valid? bool :error string}...]}"
  [result validators]
  (let [validations (map (fn [[name validator]]
                           (let [v (validator result)]
                             (assoc v :validator-name (str name))))
                         validators)]
    {:all-valid? (every? :valid? validations)
     :validations validations}))

;; ============================================================================
;; Common Validators
;; ============================================================================

(def validators
  "Common validation functions for test results."
  {:completed?
   (fn [result]
     {:valid? (= :completed (:status result))
      :error (when (not= :completed (:status result))
               (str "Expected :completed, got " (:status result)))})

   :has-artifacts?
   (fn [result]
     (let [artifacts (get-in result [:results :artifacts])]
       {:valid? (seq artifacts)
        :error (when (empty? artifacts) "No artifacts produced")}))

   :no-errors?
   (fn [result]
     {:valid? (nil? (:error result))
      :error (:error result)})

   :valid-clojure?
   (fn [result]
     (let [code-artifacts (->> (get-in result [:results :artifacts])
                               (filter #(= :code (:artifact/type %))))]
       (if (empty? code-artifacts)
         {:valid? true}
         (try
           (doseq [art code-artifacts]
             (let [_parsed (read-string (:artifact/content art))]))
           {:valid? true}
           (catch Exception _e
             {:valid? false
              :error "Invalid Clojure code"})))))})

;; ============================================================================
;; Test Specs
;; ============================================================================

(def test-specs
  "Predefined test specifications."
  {:simple-function
   {:title "Create string utility function"
    :description "Create a pure Clojure function named `snake->kebab` that converts
                  snake_case strings to kebab-case. Example: \"hello_world\" -> \"hello-world\".
                  The function should handle empty strings and nil gracefully."}

   :add-docstring
   {:title "Add docstring to function"
    :description "Add a comprehensive docstring to the following function:
                  (defn process-items [items opts]
                    (map #(update % :status (fnil inc 0)) items))"}

   :fix-bug
   {:title "Fix nil handling bug"
    :description "The following function throws NPE on nil input. Fix it:
                  (defn format-name [m] (str (:first m) \" \" (:last m)))"}

   :create-test
   {:title "Create test for function"
    :description "Create clojure.test tests for this function:
                  (defn clamp [x min-val max-val]
                    (max min-val (min max-val x)))"}})

;; ============================================================================
;; Integration Test Helpers
;; ============================================================================

(defn cli-available?
  "Check if a CLI backend is available."
  [backend]
  (let [cmd (case backend
              :claude "claude"
              :cursor "cursor"
              (name backend))]
    (try
      (zero? (:exit (shell/sh "which" cmd)))
      (catch Exception _ false))))

(defn run-simple-completion
  "Run a simple completion to verify CLI is working.
   Returns {:success? bool :response string :error string}"
  [backend]
  (when (cli-available? backend)
    (let [client (llm/create-client {:backend backend})
          response (llm/chat client "Reply with only the word 'hello'")]
      {:success? (llm/success? response)
       :response (llm/get-content response)
       :error (when-not (llm/success? response)
                (str (llm/get-error response)))})))

;; ============================================================================
;; Meta-loop Test Helpers
;; ============================================================================

(defn run-with-self-healing
  "Execute workflow with meta-loop self-healing enabled.

   If the workflow fails, the operator will:
   1. Analyze the failure
   2. Propose improvements
   3. Apply approved improvements
   4. Retry the workflow

   Options:
   - :max-retries - Maximum self-healing attempts (default 3)
   - :auto-approve? - Auto-approve operator improvements (default true for tests)"
  [{:keys [_orchestrator operator] :as env} spec {:keys [max-retries auto-approve?]
                                                   :or {max-retries 3
                                                        auto-approve? true}}]
  (loop [attempt 1
         results []]
    (let [result (execute-workflow env spec)]
      (cond
        ;; Success - return results
        (= :completed (:status result))
        {:success? true
         :attempts attempt
         :final-result result
         :history results}

        ;; Max retries exceeded
        (>= attempt max-retries)
        {:success? false
         :attempts attempt
         :final-result result
         :history (conj results result)
         :error "Max retries exceeded"}

        ;; Try self-healing via operator
        operator
        (let [_signal (operator/observe-signal operator
                                                {:type :workflow-failed
                                                 :data {:result result
                                                        :attempt attempt}})
              _improvements (operator/analyze-patterns operator 0)
              proposals (operator/get-proposals operator {:status :proposed})]
          ;; Auto-approve improvements in test mode
          (when auto-approve?
            (doseq [p proposals]
              (operator/apply-improvement operator (:improvement/id p))))
          ;; Retry
          (recur (inc attempt) (conj results result)))

        ;; No operator - fail immediately
        :else
        {:success? false
         :attempts attempt
         :final-result result
         :history results
         :error "Workflow failed and no operator available for self-healing"}))))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Create mock orchestrator for unit tests
  (def mock-env (create-mock-orchestrator {:output "42"}))

  ;; Create real orchestrator for integration tests
  (def real-env (create-real-orchestrator {:backend :claude
                                            :budget :integration}))

  ;; Check CLI availability
  (cli-available? :claude)  ;; => true
  (cli-available? :cursor)  ;; => true

  ;; Run simple completion test
  (run-simple-completion :claude)

  ;; Execute a simple workflow
  (execute-workflow real-env (:simple-function test-specs))

  ;; Validate results
  (let [result (execute-workflow real-env (:simple-function test-specs))]
    (validate-result result
                     {:completed? (:completed? validators)
                      :no-errors? (:no-errors? validators)}))

  ;; Clean up
  (cleanup-test-state!)

  :end)
