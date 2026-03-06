(ns ai.miniforge.workflow.simple-workflow-execution-test
  "Integration tests for executing simple test workflows."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [ai.miniforge.agent.interface :as agent]))

;; ============================================================================
;; Helper functions
;; ============================================================================

(defn load-workflow-edn
  "Load a workflow EDN file from resources."
  [workflow-id version]
  (let [filename (str "workflows/" (name workflow-id) "-v" version ".edn")
        resource (io/resource filename)]
    (when resource
      (edn/read-string (slurp resource)))))

(defn create-mock-context
  "Create a mock execution context for testing."
  [& {:keys [responses]
      :or {responses [{:content "(defn test [] true)"
                       :usage {:input-tokens 50 :output-tokens 25}}]}}]
  {:llm-backend (agent/create-mock-llm responses)})

;; ============================================================================
;; Workflow loading tests
;; ============================================================================

(deftest load-simple-test-workflow
  (testing "Can load simple-test-v1.0.0 workflow"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")]
      (is (some? workflow)
          "Workflow should load from resources")
      (is (= :simple-test-v1 (:workflow/id workflow)))
      (is (= 3 (count (:workflow/phases workflow)))))))

(deftest load-minimal-test-workflow
  (testing "Can load minimal-test-v1.0.0 workflow"
    (let [workflow (load-workflow-edn :minimal-test "1.0.0")]
      (is (some? workflow)
          "Workflow should load from resources")
      (is (= :minimal-test-v1 (:workflow/id workflow)))
      (is (= 1 (count (:workflow/phases workflow)))))))

;; ============================================================================
;; Phase structure tests
;; ============================================================================

(deftest simple-workflow-phase-order
  (testing "simple-test phases are in correct order"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          phase-ids (mapv :phase/id (:workflow/phases workflow))]
      (is (= [:plan :implement :done] phase-ids)
          "Phases should be ordered: plan, implement, done"))))

(deftest simple-workflow-agents
  (testing "simple-test uses correct agents"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          phases (:workflow/phases workflow)
          agents (mapv :phase/agent phases)]
      (is (= [:planner :implementer :none] agents)
          "Should use planner, implementer, and none agents"))))

;; ============================================================================
;; Configuration validation tests
;; ============================================================================

(deftest simple-workflow-budgets
  (testing "simple-test has reasonable budgets for testing"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          phases (:workflow/phases workflow)
          total-tokens (reduce + (map #(get-in % [:phase/budget :tokens]) phases))]

      (is (<= total-tokens 20000)
          "Total phase tokens should not exceed workflow max-total-tokens")

      (is (every? #(pos? (get-in % [:phase/budget :tokens]))
                  (filter #(not= :none (:phase/agent %)) phases))
          "All non-none phases should have positive token budgets"))))

(deftest minimal-workflow-efficiency
  (testing "minimal-test is optimized for speed"
    (let [workflow (load-workflow-edn :minimal-test "1.0.0")
          config (:workflow/config workflow)]

      (is (= 5 (:max-total-iterations config))
          "Should have minimal iteration limit")

      (is (= 60 (:max-total-time-seconds config))
          "Should complete within 1 minute")

      (is (= 5000 (:max-total-tokens config))
          "Should have minimal token budget"))))

;; ============================================================================
;; Gate configuration tests
;; ============================================================================

(deftest simple-workflow-gates
  (testing "simple-test has appropriate gates"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          phases (:workflow/phases workflow)
          implement-phase (first (filter #(= :implement (:phase/id %)) phases))]

      (is (= [:syntax-valid] (:phase/gates implement-phase))
          "Implement phase should have syntax gate")

      ;; Plan phase should have no gates
      (let [plan-phase (first (filter #(= :plan (:phase/id %)) phases))]
        (is (empty? (:phase/gates plan-phase))
            "Plan phase should have no gates for simplicity")))))

(deftest minimal-workflow-no-gates
  (testing "minimal-test has no gates for speed"
    (let [workflow (load-workflow-edn :minimal-test "1.0.0")
          phases (:workflow/phases workflow)]

      (is (every? #(empty? (:phase/gates %)) phases)
          "All phases should have no gates"))))

;; ============================================================================
;; Inner loop configuration tests
;; ============================================================================

(deftest simple-workflow-inner-loops
  (testing "simple-test inner loops are configured"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          phases (:workflow/phases workflow)]

      (doseq [phase phases]
        (let [inner-loop (:phase/inner-loop phase)]
          (is (some? inner-loop)
              (str "Phase " (:phase/id phase) " should have inner loop config"))

          (is (pos? (:max-iterations inner-loop))
              (str "Phase " (:phase/id phase) " should have positive max-iterations"))

          (is (or (= :none (:phase/agent phase))
                  (seq (:validation-steps inner-loop)))
              (str "Non-none phase " (:phase/id phase) " should have validation steps or be :none")))))))

;; ============================================================================
;; Workflow comparison tests
;; ============================================================================

(deftest simple-vs-minimal-comparison
  (testing "simple-test is more comprehensive than minimal-test"
    (let [simple (load-workflow-edn :simple-test "1.0.0")
          minimal (load-workflow-edn :minimal-test "1.0.0")]

      (is (> (count (:workflow/phases simple))
             (count (:workflow/phases minimal)))
          "simple-test should have more phases")

      (is (> (get-in simple [:workflow/config :max-total-tokens])
             (get-in minimal [:workflow/config :max-total-tokens]))
          "simple-test should have larger token budget")

      (is (> (get-in simple [:workflow/config :max-total-time-seconds])
             (get-in minimal [:workflow/config :max-total-time-seconds]))
          "simple-test should allow more time"))))

;; ============================================================================
;; Workflow suitability tests
;; ============================================================================

(deftest simple-workflow-use-cases
  (testing "simple-test is suitable for its intended use cases"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          task-types (:workflow/task-types workflow)]

      (is (some #{:test} task-types)
          "Should be tagged for testing use case")

      (is (some #{:demo} task-types)
          "Should be tagged for demo use case")

      (is (some #{:tutorial} task-types)
          "Should be tagged for tutorial use case"))))

(deftest minimal-workflow-use-cases
  (testing "minimal-test is suitable for unit testing"
    (let [workflow (load-workflow-edn :minimal-test "1.0.0")
          task-types (:workflow/task-types workflow)]

      (is (some #{:test} task-types)
          "Should be tagged for testing")

      (is (some #{:unit-test} task-types)
          "Should be specifically tagged for unit testing"))))

;; ============================================================================
;; Mock execution tests (without full configurable runner)
;; ============================================================================

(deftest simple-workflow-mock-phases
  (testing "simple-test phases can be executed in sequence"
    (let [workflow (load-workflow-edn :simple-test "1.0.0")
          phases (:workflow/phases workflow)
          _context (create-mock-context)]

      ;; Verify we can iterate through phases
      (is (= :plan (:workflow/entry workflow))
          "Should start with plan phase")

      (let [plan-phase (first (filter #(= :plan (:phase/id %)) phases))
            implement-phase (first (filter #(= :implement (:phase/id %)) phases))
            done-phase (first (filter #(= :done (:phase/id %)) phases))]

        ;; Check phase connections
        (is (= :implement (get-in plan-phase [:phase/next 0 :target]))
            "Plan should connect to implement")

        (is (= :done (get-in implement-phase [:phase/next 0 :target]))
            "Implement should connect to done")

        (is (empty? (:phase/next done-phase))
            "Done should be terminal")))))

;; ============================================================================
;; Workflow validation summary tests
;; ============================================================================

(deftest all-test-workflows-valid
  (testing "All test workflows pass basic validation"
    (doseq [[wf-id version] [[:simple-test "1.0.0"]
                              [:minimal-test "1.0.0"]]]
      (let [workflow (load-workflow-edn wf-id version)]

        ;; Required fields
        (is (some? (:workflow/id workflow))
            (str wf-id " should have ID"))
        (is (some? (:workflow/version workflow))
            (str wf-id " should have version"))
        (is (some? (:workflow/entry workflow))
            (str wf-id " should have entry phase"))
        (is (seq (:workflow/phases workflow))
            (str wf-id " should have phases"))
        (is (some? (:workflow/config workflow))
            (str wf-id " should have config"))

        ;; Entry phase exists
        (let [entry-id (:workflow/entry workflow)
              phase-ids (set (map :phase/id (:workflow/phases workflow)))]
          (is (contains? phase-ids entry-id)
              (str wf-id " entry phase should exist in phases")))))))
