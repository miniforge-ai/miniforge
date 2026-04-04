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

(ns ai.miniforge.meta-runner
  "Meta-Meta Loop Runner.

   This namespace provides functions to execute miniforge workflows
   using the configured real LLM backend, enabling miniforge to work on
   improving itself (dogfooding).

   The meta-meta loop (human operator) uses this to:
   1. Start workflows that improve miniforge
   2. Monitor progress through logging and artifacts
   3. Intervene when the meta-loop cannot self-correct"
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.orchestrator.interface :as orch]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.operator.interface :as operator]
   [ai.miniforge.logging.interface :as log]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [babashka.fs :as fs]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-budget
  "Default budget for dogfooding workflows."
  {:max-tokens 100000
   :max-cost-usd 2.0
   :timeout-ms (* 30 60 1000)}) ; 30 minutes

(def artifacts-dir
  "Directory for workflow artifacts."
  "artifacts")

;; ============================================================================
;; Environment Setup
;; ============================================================================

(defn create-dogfood-env
  "Create a complete environment for dogfooding with real LLM.

   Options:
   - :budget - Override default budget
   - :with-operator? - Include operator for meta-loop (default true)
   - :artifact-dir - Directory for artifacts (default 'artifacts')
   - :log-level - Logging level (default :info)"
  [{:keys [budget with-operator? artifact-dir log-level]
    :or {budget default-budget
         with-operator? true
         artifact-dir artifacts-dir
         log-level :info}}]
  (let [;; Ensure artifacts directory exists
        _ (fs/create-dirs artifact-dir)

        ;; Create logger with file output
        logger (log/create-logger {:min-level log-level})

        ;; Create stores
        k-store (knowledge/create-store)

        ;; Initialize knowledge store with rules and documentation
        _ (knowledge/initialize-knowledge-store!
           k-store
           {:on-progress (fn [info]
                          (case (:phase info)
                            :rules (case (:event info)
                                    :start (println "📚 Loading rules from" (:dir info) "...")
                                    :complete (do
                                               (println "  ✅ Loaded" (:loaded info) "rules")
                                               (when (pos? (:failed info))
                                                 (println "  ⚠️  Failed to load" (:failed info) "rules")))
                                    nil)
                            :docs (case (:event info)
                                   :start (println "📖 Loading project documentation from" (:dir info) "...")
                                   :complete (do
                                              (when (pos? (:loaded info))
                                                (println "  ✅ Loaded" (:loaded info) "documentation files:" (str/join ", " (:files info))))
                                              (when (zero? (:loaded info))
                                                (println "  ℹ️  No documentation files found")))
                                   nil)
                            :complete (println "🎉 Knowledge store initialized with" (:total info) "zettels")
                            nil))})

        a-store-dir (str artifact-dir "/datalevin-" (System/currentTimeMillis))
        _ (fs/create-dirs a-store-dir)
        a-store (artifact/create-store {:dir a-store-dir})

        ;; Create LLM client using configured backend so dogfooding can
        ;; continue when one provider is rate limited.
        llm-backend (get-in (config/load-merged-config) [:llm :backend] :codex)
        llm-client (llm/create-client {:backend llm-backend :logger logger})

        ;; Create operator if requested
        op (when with-operator?
             (operator/create-operator {:knowledge-store k-store}))

        ;; Create orchestrator
        orchestrator (orch/create-orchestrator llm-client k-store a-store
                                                {:logger logger
                                                 :operator op})]

    {:orchestrator orchestrator
     :llm-client llm-client
     :llm-backend llm-backend
     :knowledge-store k-store
     :artifact-store a-store
     :operator op
     :logger logger
     :budget budget
     :artifact-dir artifact-dir}))

;; ============================================================================
;; Workflow Execution
;; ============================================================================

(defn load-spec
  "Load a spec from an EDN file."
  [spec-path]
  (when (fs/exists? spec-path)
    (edn/read-string (slurp spec-path))))

(defn execute-dogfood-workflow
  "Execute a workflow for dogfooding.

   Arguments:
   - env - Environment from create-dogfood-env
   - spec - Workflow specification map {:title :description ...}

   Returns:
   {:workflow-id uuid
    :status keyword
    :results {...}
    :duration-ms long}"
  [{:keys [orchestrator budget logger]} spec]
  (log/info logger :meta-runner :meta-runner/workflow-starting
            {:data {:title (:title spec)}})

  (let [start-time (System/currentTimeMillis)
        result (orch/execute-workflow orchestrator spec {:budget budget})
        duration (- (System/currentTimeMillis) start-time)]

    (log/info logger :meta-runner :meta-runner/workflow-completed
              {:data {:workflow-id (:workflow-id result)
                      :status (:status result)
                      :duration-ms duration}})

    (assoc result :duration-ms duration)))

(defn get-workflow-status
  "Get status of a workflow."
  [{:keys [orchestrator]} workflow-id]
  (orch/get-workflow-status orchestrator workflow-id))

(defn get-workflow-results
  "Get results of a completed workflow."
  [{:keys [orchestrator]} workflow-id]
  (orch/get-workflow-results orchestrator workflow-id))

;; ============================================================================
;; Meta-Loop Monitoring
;; ============================================================================

(defn get-meta-loop-status
  "Get status of the meta-loop (operator)."
  [{:keys [operator]}]
  (when operator
    {:signals (operator/get-signals operator {:limit 20})
     :pending-improvements (operator/get-proposals operator {:status :proposed})
     :recent-improvements (operator/get-proposals operator {:status :applied})}))

(defn approve-improvement
  "Approve a pending improvement."
  [{:keys [operator]} improvement-id]
  (when operator
    (operator/apply-improvement operator improvement-id)))

(defn reject-improvement
  "Reject a pending improvement."
  [{:keys [operator]} improvement-id reason]
  (when operator
    (operator/reject-improvement operator improvement-id reason)))

;; ============================================================================
;; Artifact Management
;; ============================================================================

(defn save-workflow-report
  "Save a workflow report to the artifacts directory."
  [{:keys [artifact-dir]} workflow-id result]
  (let [report-path (str artifact-dir "/workflow-" workflow-id ".edn")]
    (spit report-path (pr-str result))
    report-path))

(defn list-workflow-reports
  "List all workflow reports in the artifacts directory."
  [{:keys [artifact-dir]}]
  (->> (fs/list-dir artifact-dir)
       (filter #(re-matches #"workflow-.*\.edn" (fs/file-name %)))
       (map str)
       sort))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn run-spec-file
  "Run a workflow from a spec file.

   Example:
     (run-spec-file \"docs/specs/reporting-component.spec.edn\")"
  [spec-path]
  (let [spec (load-spec spec-path)]
    (if spec
      (let [env (create-dogfood-env {})
            result (execute-dogfood-workflow env spec)
            report-path (save-workflow-report env (:workflow-id result) result)]
        (println "Workflow completed:" (:status result))
        (println "Report saved to:" report-path)
        result)
      (println "Could not load spec from:" spec-path))))

(defn quick-task
  "Run a quick task with minimal spec.

   Example:
     (quick-task \"Add docstrings to components/task/src/ai/miniforge/task/core.clj\")"
  [description]
  (let [env (create-dogfood-env {:budget {:max-tokens 50000
                                          :max-cost-usd 1.0
                                          :timeout-ms (* 10 60 1000)}})
        spec {:title "Quick task"
              :description description}]
    (execute-dogfood-workflow env spec)))

;; ============================================================================
;; Rich Comment - Meta-Meta Loop Entry Point
;; ============================================================================

(comment
  ;; === STARTING THE META-META LOOP ===

  ;; 1. Create the dogfooding environment
  (def env (create-dogfood-env {}))

  ;; 2. Load the reporting component spec
  (def spec (load-spec "docs/specs/reporting-component.spec.edn"))

  ;; 3. Execute the workflow (miniforge works on miniforge!)
  (def result (execute-dogfood-workflow env spec))

  ;; 4. Monitor progress
  (get-workflow-status env (:workflow-id result))

  ;; 5. Check meta-loop status
  (get-meta-loop-status env)

  ;; 6. View pending improvements from operator
  (-> (get-meta-loop-status env) :pending-improvements)

  ;; 7. Approve or reject improvements
  ;; (approve-improvement env some-improvement-id)
  ;; (reject-improvement env some-improvement-id "reason")

  ;; 8. Get final results
  (get-workflow-results env (:workflow-id result))

  ;; 9. Save report
  (save-workflow-report env (:workflow-id result) result)

  ;; === QUICK DOGFOODING TASKS ===

  ;; Run a simple task
  (quick-task "Add docstring to the clamp function in components/task/")

  ;; Run reporting component creation
  (run-spec-file "docs/specs/reporting-component.spec.edn")

  :end)
