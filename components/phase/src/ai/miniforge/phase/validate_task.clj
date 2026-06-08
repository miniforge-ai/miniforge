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

(ns ai.miniforge.phase.validate-task
  "CI gate for phase transition graph structural validation.

   Entry point: `run` — called by the bb phases:validate task.

   Loads the default pipeline from config/phase/defaults.edn (direct EDN read)
   and graph options from config/phase/graph.edn, builds the transition graph
   via graph/build-transition-graph, then validates structural properties 1–6
   via graph-validator/validate-graph.

   Property 7 (interceptors) is intentionally skipped in this bb context.
   Babashka has no JVM defmethod registry, so phase interceptors registered
   via defmethod are unavailable. The JVM load-time path exercises all 7
   properties via ai.miniforge.phase.interface/validate-pipeline.

   Exits 0 on success, 1 on failure (non-zero for CI gate usage).
   All output strings are looked up via system-locale msg/t (rule 050)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.graph :as graph]
   [ai.miniforge.phase.graph-validator :as graph-validator]
   [ai.miniforge.phase.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Named constants

(def ^:private defaults-resource-path
  "Classpath-relative path to the default pipeline EDN config."
  "config/phase/defaults.edn")

(def ^:private graph-config-resource-path
  "Classpath-relative path to the graph operational config (budget-guarded phases)."
  "config/phase/graph.edn")

;------------------------------------------------------------------------------ Layer 0
;; Config loaders (pure I/O — no side effects beyond reading classpath resources)

(defn- load-edn-resource
  "Read and parse an EDN classpath resource. Returns nil when the resource is absent."
  [resource-path]
  (when-let [res (io/resource resource-path)]
    (edn/read-string (slurp res))))

(defn- load-default-pipeline
  "Read the canonical default pipeline from config/phase/defaults.edn.
   Returns the :phase/pipeline vector."
  []
  (-> (load-edn-resource defaults-resource-path)
      (get :phase/pipeline)))

(defn- load-graph-opts
  "Read graph configuration from config/phase/graph.edn.
   Returns a build-transition-graph opts map, or nil when the resource is absent."
  []
  (when-let [cfg (load-edn-resource graph-config-resource-path)]
    (when-let [guarded (get cfg :phase/budget-guarded-phases)]
      {:budget-guarded-phases guarded})))

;------------------------------------------------------------------------------ Layer 0
;; Violation formatting

(defn- anomaly-phase-label
  "Extract a display label for the phase involved in a violation.
   Checks :node first (most checks), then :edge :from (existence check),
   then :cycle-nodes first element (cycle check), then falls back to :graph."
  [anomaly-data]
  (or (:node anomaly-data)
      (get-in anomaly-data [:edge :from])
      (first (:cycle-nodes anomaly-data))
      :graph))

(defn- format-violation
  "Format a single violation anomaly map for stderr output.
   Outputs phase name (from :anomaly/data), the diagnostic message, and raw data."
  [error]
  (let [data  (:anomaly/data error)
        phase (anomaly-phase-label data)
        msg   (:anomaly/message error)]
    (messages/ts :validate-task/violation-format
                 {:phase   (name phase)
                  :message msg
                  :data    (pr-str data)})))

;------------------------------------------------------------------------------ Layer 1
;; Validation orchestration

(defn validate-default-pipeline
  "Build and structurally validate the default pipeline.
   Returns an anomaly map on build failure, or
   {:valid? bool :node-count int :edge-count int :errors [anomaly...]}."
  []
  (let [pipeline (load-default-pipeline)
        opts     (or (load-graph-opts) {})
        g        (graph/build-transition-graph pipeline opts)]
    (if (anomaly/anomaly? g)
      g
      (let [{:keys [valid? errors]} (graph-validator/validate-graph g)
            node-count              (count (:nodes g))
            edge-count              (count (:edges g))]
        {:valid?      valid?
         :node-count  node-count
         :edge-count  edge-count
         :errors      errors}))))

;------------------------------------------------------------------------------ Layer 1
;; Output handlers

(defn- emit-success
  "Print the success summary to stdout."
  [{:keys [node-count edge-count]}]
  (println (messages/ts :validate-task/summary-valid
                        {:node-count node-count
                         :edge-count edge-count})))

(defn- emit-failures
  "Print the failure summary and each violation to stderr."
  [{:keys [errors]}]
  (binding [*out* *err*]
    (println (messages/ts :validate-task/summary-invalid
                          {:error-count (count errors)}))
    (doseq [error errors]
      (println (format-violation error)))))

(defn- emit-build-error
  "Print a graph-build error to stderr."
  [anomaly-map]
  (binding [*out* *err*]
    (println (messages/ts :validate-task/build-error
                          {:message (:anomaly/message anomaly-map)}))))

;------------------------------------------------------------------------------ Layer 1
;; Process exit — var wrapper enables test redefinition

(def ^:private exit-process!
  "Thin var wrapper around System/exit. Using a var (not a direct static call)
   allows tests to intercept exit via with-redefs."
  (fn [code] (System/exit code)))

;------------------------------------------------------------------------------ Layer 2
;; Entry point

(defn run
  "CI entry point called by the bb phases:validate task.

   Validates structural properties 1–6 of the default phase transition graph:
     1. Existence       — no dangling edge targets
     2. Termination     — every non-terminal node reaches a terminal
     3. Cycles          — no runaway (un-bounded) cycles
     4. Retry bounds    — retry self-loops have iteration guards
     5. Failure paths   — every phase has a failure edge to terminal
     6. Budget paths    — budget-guarded phases have exhaustion-to-terminal paths

   Property 7 (interceptors) is skipped: babashka has no JVM defmethod
   registry. The JVM path exercises all 7 via interface/validate-pipeline.

   Exits 0 on success, 1 on failure."
  []
  (let [result (validate-default-pipeline)]
    (cond
      (anomaly/anomaly? result)
      (do (emit-build-error result)
          (exit-process! 1))

      (:valid? result)
      (do (emit-success result)
          (exit-process! 0))

      :else
      (do (emit-failures result)
          (exit-process! 1)))))

(comment
  ;; Quick REPL validation — does the default pipeline pass?
  (validate-default-pipeline)
  ;; => {:valid? true :node-count N :edge-count M :errors []}

  ;; Load pipeline and graph config individually
  (load-default-pipeline)
  (load-graph-opts)

  ;; Smoke-test format-violation with a synthetic anomaly
  (format-violation
   {:anomaly/message "graph-validator: no terminal node is reachable from this node via BFS — stall risk"
    :anomaly/data    {:node :verify}})

  :leave-this-here)
