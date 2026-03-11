(ns test-runner
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))

(defn run-stream! [& args]
  (let [{:keys [exit]} (deref (apply p/process {:out :inherit :err :inherit} args))]
    exit))

(defn integration []
  (println "🧪 Running project integration tests...")
  (let [deps (slurp "projects/miniforge/deps.edn")
        test-nses ["ai.miniforge.workflow.release-integration-test"
                   "ai.miniforge.workflow.loader-integration-test"
                   "ai.miniforge.workflow.configurable-integration-test"
                   "ai.miniforge.workflow.runner-integration-test"
                   "ai.miniforge.workflow.financial-etl-test"
                   "ai.miniforge.workflow.dag-orchestrator-test"
                   "ai.miniforge.workflow.artifact-persistence-test"
                   "ai.miniforge.dag-executor.executor-test"
                   "ai.miniforge.orchestrator.interface-test"
                   "ai.miniforge.llm.progress-monitor-test"
                   "ai.miniforge.phase.handoff-test"
                   "ai.miniforge.tool-registry.integration-test"
                   "ai.miniforge.self-healing.backend-health-test"
                   "ai.miniforge.tui-views.subscription-test"
                   "ai.miniforge.tui-engine.core-test"
                   "ai.miniforge.web-dashboard.interface-test"
                   "ai.miniforge.tui-views.pr-views-test"
                   "ai.miniforge.tui-views.view-test"
                   "ai.miniforge.tui-views.persistence-test"
                   "ai.miniforge.knowledge.interface-test"
                   "ai.miniforge.gate.pipeline-test"
                   "ai.miniforge.evidence-bundle.interface-test"
                   "ai.miniforge.artifact.interface-integration-test"
                   "ai.miniforge.loop.interface-integration-test"
                   "ai.miniforge.observer.interface-integration-test"
                   "ai.miniforge.task.interface-integration-test"
                   "ai.miniforge.release-executor.artifact-validation-integration-test"
                   "ai.miniforge.web-dashboard.fleet-onboarding-integration-test"
                   "ai.miniforge.self-healing.integration-test"
                   "ai.miniforge.governance.e2e-test"
                   "ai.miniforge.mcp.artifact-server-integration-test"]
        require-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        run-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        expr (str "(require 'clojure.test" require-expr ") "
                  "(let [r (clojure.test/run-tests" run-expr ")] "
                  "  (System/exit (if (zero? (+ (:fail r 0) (:error r 0))) 0 1)))")
        {:keys [exit out err]}
        (p/sh {:out :string :err :string :dir "projects/miniforge"}
              "clojure" "-Sdeps" deps "-M" "-e" expr)]
    (when-not (str/blank? out) (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (when-not (zero? exit)
      (println "❌ Integration tests failed with exit code:" exit)
      (System/exit exit))))

(defn conformance []
  (println "🧪 Running N1 conformance tests...")
  (let [test-nses ["conformance.n1_architecture_test"
                   "conformance.event_stream_test"
                   "conformance.agent_context_handoff_test"
                   "conformance.protocol_conformance_test"
                   "conformance.gate_enforcement_test"]
        require-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        run-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        expr (str "(require 'clojure.test" require-expr ") "
                  "(clojure.test/run-tests" run-expr ")")
        exit (run-stream! "clojure" "-M:conformance" "-e" expr)]
    (when-not (zero? exit)
      (println "❌ Conformance tests failed with exit code:" exit)
      (System/exit exit))))

(defn graalvm []
  (println "🧪 Testing GraalVM/Babashka compatibility...")
  (let [;; Use :dev alias to get full component classpath
        cp (-> (p/sh {:out :string} "clojure" "-A:dev" "-Spath")
               :out
               str/trim)
        ;; Add tests directory to classpath
        full-cp (str cp ":tests")
        expr "(require 'graalvm-compatibility-test) (graalvm-compatibility-test/-main)"
        exit (run-stream! "bb" "-cp" full-cp "-e" expr)]
    (when-not (zero? exit)
      (println "❌ GraalVM compatibility tests failed with exit code:" exit)
      (System/exit exit))))
