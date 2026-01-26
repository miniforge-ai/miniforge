(ns conformance.n1_basic_conformance_test
  "Basic N1 Architecture conformance tests.
   Verifies core N1 requirements are met by the implementation."
  (:require
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 1
;; N1 §4: Polylith Component Boundaries

(deftest polylith-components-exist-test
  (testing "N1 §4.1: All required components exist"
    (let [required-components
          #{:schema :logging :llm :tool :agent :task :loop
            :workflow :knowledge :policy :policy-pack :heuristic
            :artifact :gate :observer}]
      (doseq [component required-components]
        (let [ns-name (symbol (str "ai.miniforge." (name component) ".interface"))]
          (try
            (require ns-name)
            (is true (str "Component " component " loads successfully"))
            (catch Exception e
              (is false (str "Component " component " should exist and load: " (.getMessage e))))))))))

;------------------------------------------------------------------------------ Layer 2
;; N1 §2: Core Domain Model - Protocol Existence

(deftest agent-protocol-exists-test
  (testing "N1 §2.3.2: Agent protocol exists"
    (require 'ai.miniforge.agent.interface.protocols.agent)
    (let [agent-proto (ns-resolve 'ai.miniforge.agent.interface.protocols.agent 'Agent)]
      (is (some? agent-proto) "Agent protocol must exist"))))

(deftest tool-protocol-exists-test
  (testing "N1 §2.5.1: Tool protocol exists"
    (require 'ai.miniforge.tool.interface.protocols.tool)
    (let [tool-proto (ns-resolve 'ai.miniforge.tool.interface.protocols.tool 'Tool)]
      (is (some? tool-proto) "Tool protocol must exist"))))

(deftest gate-protocol-exists-test
  (testing "N1 §2.6.1: Gate protocol exists"
    (require 'ai.miniforge.gate.interface.protocols.gate)
    (let [gate-proto (ns-resolve 'ai.miniforge.gate.interface.protocols.gate 'Gate)]
      (is (some? gate-proto) "Gate protocol must exist"))))

;------------------------------------------------------------------------------ Layer 2
;; N1 §2: Core Domain Model - Schema Definitions

(deftest workflow-schema-exists-test
  (testing "N1 §2.1: Workflow schema is defined"
    (require 'ai.miniforge.workflow.interface)
    (let [workflow-ns (find-ns 'ai.miniforge.workflow.interface)]
      (is (some? workflow-ns) "Workflow interface namespace must exist")
      ;; Check for key workflow functions
      (is (some? (ns-resolve workflow-ns 'start)) "Workflow start function exists")
      (is (some? (ns-resolve workflow-ns 'get-state)) "Workflow get-state function exists")
      (is (some? (ns-resolve workflow-ns 'advance)) "Workflow advance function exists"))))

(deftest phase-schema-exists-test
  (testing "N1 §2.2: Phase schema is defined"
    (require 'ai.miniforge.phase.interface)
    (let [phase-ns (find-ns 'ai.miniforge.phase.interface)]
      (is (some? phase-ns) "Phase interface namespace must exist"))))

(deftest evidence-bundle-schema-exists-test
  (testing "N1 §2.8: Evidence bundle schema is defined"
    (require 'ai.miniforge.evidence-bundle.interface)
    (let [evidence-ns (find-ns 'ai.miniforge.evidence-bundle.interface)]
      (is (some? evidence-ns) "Evidence bundle interface namespace must exist")
      ;; Check for key evidence functions
      (is (some? (ns-resolve evidence-ns 'create-bundle)) "Evidence create-bundle function exists")
      (is (some? (ns-resolve evidence-ns 'get-bundle)) "Evidence get-bundle function exists"))))

(deftest artifact-schema-exists-test
  (testing "N1 §2.9: Artifact schema is defined"
    (require 'ai.miniforge.artifact.interface)
    (let [artifact-ns (find-ns 'ai.miniforge.artifact.interface)]
      (is (some? artifact-ns) "Artifact interface namespace must exist")
      (is (some? (ns-resolve artifact-ns 'create-store)) "Artifact create-store function exists")
      (is (some? (ns-resolve artifact-ns 'build-artifact)) "Artifact build-artifact function exists"))))

;------------------------------------------------------------------------------ Layer 2
;; N1 §3: Three-Layer Architecture

(deftest control-plane-components-exist-test
  (testing "N1 §3.1: Control Plane components exist"
    (require 'ai.miniforge.operator.interface)
    (require 'ai.miniforge.workflow.interface)
    (require 'ai.miniforge.policy.interface)
    (is true "Operator, Workflow, and Policy components load")))

(deftest agent-layer-components-exist-test
  (testing "N1 §3.2: Agent Layer components exist"
    (require 'ai.miniforge.agent.interface)
    (require 'ai.miniforge.loop.interface)
    (require 'ai.miniforge.tool.interface)
    (is true "Agent, Loop, and Tool components load")))

(deftest learning-layer-components-exist-test
  (testing "N1 §3.3: Learning Layer components exist"
    (require 'ai.miniforge.observer.interface)
    (require 'ai.miniforge.knowledge.interface)
    (require 'ai.miniforge.heuristic.interface)
    (is true "Observer, Knowledge, and Heuristic components load")))

;------------------------------------------------------------------------------ Layer 2
;; N1 §7: Inner Loop & Outer Loop

(deftest inner-loop-exists-test
  (testing "N1 §7.2: Inner loop implementation exists"
    (require 'ai.miniforge.loop.inner)
    (let [inner-ns (find-ns 'ai.miniforge.loop.inner)]
      (is (some? inner-ns) "Inner loop namespace must exist")
      (is (some? (ns-resolve inner-ns 'run-inner-loop)) "run-inner-loop function exists")
      (is (some? (ns-resolve inner-ns 'create-inner-loop)) "create-inner-loop function exists"))))

(deftest gates-implementation-exists-test
  (testing "N1 §7.2.1: Gates implementation exists"
    (require 'ai.miniforge.loop.gates)
    (let [gates-ns (find-ns 'ai.miniforge.loop.gates)]
      (is (some? gates-ns) "Gates namespace must exist")
      (is (some? (ns-resolve gates-ns 'syntax-gate)) "syntax-gate function exists")
      (is (some? (ns-resolve gates-ns 'lint-gate)) "lint-gate function exists")
      (is (some? (ns-resolve gates-ns 'policy-gate)) "policy-gate function exists"))))

(deftest repair-implementation-exists-test
  (testing "N1 §7.2: Repair implementation exists"
    (require 'ai.miniforge.loop.repair)
    (let [repair-ns (find-ns 'ai.miniforge.loop.repair)]
      (is (some? repair-ns) "Repair namespace must exist")
      (is (some? (ns-resolve repair-ns 'default-strategies)) "default-strategies function exists"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'conformance.n1_basic_conformance_test)

  :leave-this-here)
