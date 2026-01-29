(ns graalvm-compatibility-test
  "GraalVM/Babashka compatibility test suite.

   This test ensures all miniforge components can be loaded in Babashka/GraalVM
   native image environments. Prevents JVM-only dependencies from being introduced.

   Run with:
     bb -cp $(clojure -Spath) -e '(require (quote graalvm-compatibility-test)) (graalvm-compatibility-test/run-tests)'

   Or add to CI/pre-commit:
     bb test:graalvm"
  (:require
   [clojure.test :refer [deftest is testing run-tests]]
   [clojure.string :as str]
   [clojure.java.io :as io]))

(defn- require-namespace
  "Try to require a namespace and return [success? error-message]."
  [ns-symbol]
  (try
    (require ns-symbol)
    [true nil]
    (catch Exception e
      [false (str "Failed to require " ns-symbol ": " (ex-message e))])))

(defn- check-for-jvm-only-features
  "Check if error message indicates JVM-only features."
  [error-msg]
  (let [jvm-only-indicators ["definterface"
                             "defprotocol"
                             "gen-class"
                             "proxy"
                             "reify with Java interfaces"]]
    (some #(str/includes? (or error-msg "") %) jvm-only-indicators)))

(deftest test-core-components-load-in-babashka
  (testing "Core components should load in Babashka/GraalVM"
    (let [core-namespaces ['ai.miniforge.schema.interface
                           'ai.miniforge.logging.interface
                           'ai.miniforge.llm.interface
                           'ai.miniforge.tool.interface
                           'ai.miniforge.artifact.interface
                           'ai.miniforge.agent.interface
                           'ai.miniforge.task.interface
                           'ai.miniforge.loop.interface
                           'ai.miniforge.knowledge.interface
                           'ai.miniforge.policy.interface
                           'ai.miniforge.heuristic.interface
                           'ai.miniforge.response.interface
                           'ai.miniforge.fsm.interface
                           'ai.miniforge.phase.interface
                           'ai.miniforge.gate.interface
                           'ai.miniforge.workflow.interface]]
      (doseq [ns-sym core-namespaces]
        (let [[success? error-msg] (require-namespace ns-sym)]
          (when-not success?
            (when (check-for-jvm-only-features error-msg)
              (is false (str "JVM-ONLY DEPENDENCY DETECTED in " ns-sym ":\n"
                           error-msg
                           "\n\nThis blocks GraalVM native compilation."
                           "\nPlease replace with Babashka-compatible alternative."))))
          (is success? (str "Should load " ns-sym " in Babashka\n" error-msg)))))))

(deftest test-no-forbidden-dependencies
  (testing "Forbidden JVM-only dependencies should not be present"
    (let [forbidden-deps {"org.clojure/data.json" "Use cheshire.core instead (GraalVM-compatible)"
                         "org.clojure/java.jdbc" "Use next.jdbc instead (GraalVM-compatible)"
                         "clojure.java.io/file" "Use babashka.fs instead"}
          ;; Read all deps.edn files in components
          component-dirs (file-seq (clojure.java.io/file "components"))
          deps-files (->> component-dirs
                         (filter #(= "deps.edn" (.getName %)))
                         (map slurp))]
      (doseq [[dep replacement] forbidden-deps
              deps-content deps-files]
        (is (not (str/includes? deps-content dep))
            (str "Found forbidden dependency: " dep "\n"
                 "Reason: Not GraalVM-compatible\n"
                 "Fix: " replacement))))))

(deftest test-llm-component-uses-cheshire
  (testing "LLM component should use cheshire (not org.clojure/data.json)"
    (let [llm-deps (slurp "components/llm/deps.edn")]
      (is (str/includes? llm-deps "cheshire")
          "LLM component should use cheshire for GraalVM compatibility")
      (is (not (str/includes? llm-deps "org.clojure/data.json"))
          "LLM component must not use org.clojure/data.json (not GraalVM-compatible)"))))

(deftest test-workflow-execution-available
  (testing "Workflow execution should be available in Babashka build"
    ;; This tests the actual functionality that was previously broken
    (let [[success? error-msg] (require-namespace 'ai.miniforge.workflow.interface)]
      (is success? (str "Workflow interface should load: " error-msg))
      (when success?
        ;; Check that we can resolve the key functions
        (is (not (nil? (resolve 'ai.miniforge.workflow.interface/load-workflow)))
            "Should be able to resolve load-workflow")
        (is (not (nil? (resolve 'ai.miniforge.workflow.interface/run-pipeline)))
            "Should be able to resolve run-pipeline")))))

(defn -main
  "Entry point for running tests from command line."
  []
  (let [result (run-tests 'graalvm-compatibility-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))

(comment
  ;; Run tests in REPL
  (run-tests 'graalvm-compatibility-test)

  ;; Test individual namespace loading
  (require-namespace 'ai.miniforge.llm.interface)

  :end)
