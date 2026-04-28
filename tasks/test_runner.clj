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

(ns test-runner
  (:require
   [ai.miniforge.bb-proc.interface :as proc]
   [babashka.process :as p]
   [clojure.string :as str]))

(defn run-stream! [& args]
  (let [[opts cmd-args] (if (map? (first args))
                          [(merge {:out :inherit :err :inherit} (first args))
                           (rest args)]
                          [{:out :inherit :err :inherit} args])
        {:keys [exit]} (deref (apply p/process opts cmd-args))]
    exit))

(defn- run-project-tests!
  [project test-nses]
  (let [deps (slurp (str "projects/" project "/deps.edn"))
        clojure-cmd (proc/clojure-command)
        require-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        run-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        expr (str "(require 'clojure.test" require-expr ") "
                  "(let [r (clojure.test/run-tests" run-expr ")] "
                  "  (System/exit (if (zero? (+ (:fail r 0) (:error r 0))) 0 1)))")]
    (run-stream! {:dir (str "projects/" project)}
                 clojure-cmd "-Sdeps" deps "-M" "-e" expr)))

(defn integration []
  (println "🧪 Running project integration tests...")
  (let [miniforge-tests ["ai.miniforge.workflow.release-integration-test"
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
                         "ai.miniforge.governance.e2e-test"]
        kernel-tests ["ai.miniforge.workflow.kernel-loader-integration-test"]
        miniforge-exit (run-project-tests! "miniforge" miniforge-tests)]
    (when-not (zero? miniforge-exit)
      (println "❌ Integration tests failed in project: miniforge")
      (System/exit miniforge-exit))
    (let [kernel-exit (run-project-tests! "miniforge-core" kernel-tests)]
      (when-not (zero? kernel-exit)
        (println "❌ Integration tests failed in project: miniforge-core")
        (System/exit kernel-exit)))))

(defn conformance []
  (println "🧪 Running N1 conformance tests...")
  (let [test-nses ["conformance.n1_architecture_test"
                   "conformance.event_stream_test"
                   "conformance.agent_context_handoff_test"
                   "conformance.protocol_conformance_test"
                   "conformance.gate_enforcement_test"]
        clojure-cmd (proc/clojure-command)
        require-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        run-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        expr (str "(require 'clojure.test" require-expr ") "
                  "(clojure.test/run-tests" run-expr ")")
        exit (run-stream! clojure-cmd "-M:conformance" "-e" expr)]
    (when-not (zero? exit)
      (println "❌ Conformance tests failed with exit code:" exit)
      (System/exit exit))))

(defn graalvm []
  (println "🧪 Testing GraalVM/Babashka compatibility...")
  (let [clojure-cmd (proc/clojure-command)
        ;; Use :dev alias to get full component classpath
        cp (-> (p/sh {:out :string} clojure-cmd "-A:dev" "-Spath")
               :out
               str/trim)
        ;; Add tests directory to classpath
        full-cp (str cp ":tests")
        expr "(require 'graalvm-compatibility-test) (graalvm-compatibility-test/-main)"
        exit (run-stream! "bb" "-cp" full-cp "-e" expr)]
    (when-not (zero? exit)
      (println "❌ GraalVM compatibility tests failed with exit code:" exit)
      (System/exit exit))))
