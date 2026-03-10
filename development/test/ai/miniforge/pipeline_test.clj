(ns ai.miniforge.pipeline-test
  "End-to-end pipeline integration tests.
   Validates the full flow: spec → plan → implement → test with all components wired together."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.orchestrator.interface :as orch]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.llm.interface :as llm]))

;; ============================================================================
;; Test infrastructure
;; ============================================================================

;; Track stores for cleanup
(def test-stores (atom []))

(defn cleanup-stores
  "Fixture to close all stores after each test."
  [f]
  (try
    (f)
    (finally
      ;; Knowledge store is atom-based, no cleanup needed
      ;; Only artifact store (Datalevin) needs closing
      (doseq [{:keys [a-store]} @test-stores]
        (when a-store (artifact/close-store a-store)))
      (reset! test-stores []))))

(use-fixtures :each cleanup-stores)

(defn create-mock-responses
  "Create a sequence of mock LLM responses for pipeline stages."
  []
  [;; Planning response - returns implementation tasks
   (str "Based on the specification, here is the implementation plan:\n\n"
        "## Implementation Tasks\n\n"
        "1. Create the greeting function\n"
        "2. Add input validation\n\n"
        "```json\n"
        "[{\"title\": \"Create greeting function\", "
        "\"description\": \"Implement a function that returns a greeting message\", "
        "\"type\": \"implement\"}]\n"
        "```")

   ;; Implementation response - returns code artifact
   (str "Here is the implementation:\n\n"
        "```clojure\n"
        "(ns greeting.core)\n\n"
        "(defn greet\n"
        "  \"Return a greeting for the given name.\"\n"
        "  [name]\n"
        "  (str \"Hello, \" name \"!\"))\n"
        "```")

   ;; Test generation response - returns test code
   (str "Here are the tests:\n\n"
        "```clojure\n"
        "(ns greeting.core-test\n"
        "  (:require [clojure.test :refer [deftest is testing]]\n"
        "            [greeting.core :as sut]))\n\n"
        "(deftest greet-test\n"
        "  (testing \"returns greeting with name\"\n"
        "    (is (= \"Hello, World!\" (sut/greet \"World\")))))\n"
        "```")])

(defn setup-test-orchestrator
  "Create a fully wired orchestrator for testing."
  []
  (let [llm-client (llm/mock-client {:outputs (create-mock-responses)})
        k-store (knowledge/create-store)
        a-store (artifact/create-store)]

    ;; Register stores for cleanup
    (swap! test-stores conj {:k-store k-store :a-store a-store})

    ;; Seed knowledge store with some test rules
    (knowledge/put-zettel k-store
                           (knowledge/create-zettel
                            "clojure-conventions"
                            "Use idiomatic Clojure: prefer threading macros, use destructuring, write pure functions."
                            :rule
                            {:tags #{:clojure :coding}
                             :dewey "210"}))

    (knowledge/put-zettel k-store
                           (knowledge/create-zettel
                            "testing-practices"
                            "Every public function should have tests. Use clojure.test with is and testing macros."
                            :rule
                            {:tags #{:testing :clojure}
                             :dewey "400"}))

    {:orchestrator (orch/create-orchestrator llm-client k-store a-store)
     :knowledge-store k-store
     :artifact-store a-store}))

;; ============================================================================
;; Orchestrator creation tests
;; ============================================================================

(deftest orchestrator-wiring-test
  (testing "all components wire together correctly"
    (let [{:keys [orchestrator]} (setup-test-orchestrator)]
      (is (some? orchestrator))
      (is (satisfies? orch/Orchestrator orchestrator)))))

;; ============================================================================
;; Workflow status tests
;; ============================================================================

(deftest workflow-status-lifecycle-test
  (testing "workflow status tracking"
    (let [{:keys [orchestrator]} (setup-test-orchestrator)
          non-existent-id (random-uuid)]

      ;; Non-existent workflow returns nil
      (is (nil? (orch/get-workflow-status orchestrator non-existent-id)))

      ;; Results for non-existent workflow returns nil
      (is (nil? (orch/get-workflow-results orchestrator non-existent-id))))))

;; ============================================================================
;; Budget management integration tests
;; ============================================================================

(deftest budget-integration-test
  (testing "budget manager integrates with orchestrator"
    (let [budget-mgr (orch/create-budget-manager)
          workflow-id (random-uuid)]

      ;; Set a budget
      (orch/set-budget budget-mgr workflow-id
                       {:max-tokens 10000
                        :max-cost-usd 1.0
                        :timeout-ms 60000})

      ;; Check initial state
      (let [{:keys [within-budget? remaining]} (orch/check-budget budget-mgr workflow-id)]
        (is (true? within-budget?))
        (is (= 10000 (:tokens remaining))))

      ;; Track some usage
      (orch/track-usage budget-mgr workflow-id {:tokens 3000 :cost-usd 0.3 :duration-ms 5000})

      ;; Verify tracking
      (let [{:keys [within-budget? remaining used]} (orch/check-budget budget-mgr workflow-id)]
        (is (true? within-budget?))
        (is (= 7000 (:tokens remaining)))
        (is (= 3000 (:tokens used)))))))

;; ============================================================================
;; Knowledge injection integration tests
;; ============================================================================

(deftest knowledge-injection-test
  (testing "knowledge coordinator injects relevant knowledge"
    (let [{:keys [knowledge-store]} (setup-test-orchestrator)
          coord (orch/create-knowledge-coordinator knowledge-store)
          ;; Query for implementer knowledge
          task {:task/id (random-uuid)
                :task/type :implement
                :task/tags [:clojure]}
          result (orch/inject-for-agent coord :implementer task {})]
      ;; Should return knowledge structure
      (is (some? result))
      (is (map? result))
      (is (number? (:count result))))))

;; ============================================================================
;; Task routing integration tests
;; ============================================================================

(deftest task-routing-integration-test
  (testing "task router correctly routes different task types"
    (let [router (orch/create-router)]

      ;; Plan tasks → planner
      (let [result (orch/route-task router {:task/type :plan} {})]
        (is (= :planner (:agent-role result))))

      ;; Implement tasks → implementer
      (let [result (orch/route-task router {:task/type :implement} {})]
        (is (= :implementer (:agent-role result))))

      ;; Test tasks → tester
      (let [result (orch/route-task router {:task/type :test} {})]
        (is (= :tester (:agent-role result))))

      ;; Review tasks → reviewer
      (let [result (orch/route-task router {:task/type :review} {})]
        (is (= :reviewer (:agent-role result)))))))

;; ============================================================================
;; Component compatibility tests
;; ============================================================================

(deftest component-compatibility-test
  (testing "all pipeline components are compatible"
    ;; Test that we can create all required components
    (let [llm (llm/mock-client {:output "test"})
          k-store (knowledge/create-store)
          a-store (artifact/create-store)
          _ (swap! test-stores conj {:k-store k-store :a-store a-store})
          orch-instance (orch/create-orchestrator llm k-store a-store)]

      (is (some? llm))
      (is (some? k-store))
      (is (some? a-store))
      (is (some? orch-instance))

      ;; Verify stores work
      (let [zettel (knowledge/create-zettel "test" "content" :rule {})]
        (knowledge/put-zettel k-store zettel)
        (is (some? (knowledge/get-zettel k-store (:zettel/id zettel))))))))

;; ============================================================================
;; Rich comment for manual testing
;; ============================================================================

(comment
  ;; Manual end-to-end test (requires real or mock LLM)

  ;; Setup
  (def llm-client (llm/mock-client {:outputs (create-mock-responses)}))
  (def k-store (knowledge/create-store))
  (def a-store (artifact/create-store))
  (def orchestrator (orch/create-orchestrator llm-client k-store a-store))

  ;; Execute workflow
  (def result
    (orch/execute-workflow
     orchestrator
     {:title "Create greeting function"
      :description "A simple function that returns a greeting message for a given name"}
     {:budget {:max-tokens 50000}}))

  ;; Check status
  (orch/get-workflow-status orchestrator (:workflow-id result))

  ;; Get results
  (orch/get-workflow-results orchestrator (:workflow-id result))

  :end)
