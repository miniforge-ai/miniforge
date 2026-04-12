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

(ns ai.miniforge.workflow.runner-cleanup
  "Post-workflow cleanup hooks.

   Fires on ALL exit paths (success, failure, exception).
   Each step is independently try-caught so one failure cannot mask another.
   Extracted from runner.clj."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.runner-environment :as env]
   [ai.miniforge.workflow.runner-events :as events]))

;------------------------------------------------------------------------------ Layer 0
;; Signal factories

(defn- make-completion-signal
  "Build a workflow signal from a completed execution context."
  [output-ctx]
  {:signal/type   (if (phase/succeeded? output-ctx) :workflow-complete :workflow-failed)
   :workflow-id   (:execution/id output-ctx)
   :phase-results (:execution/phase-results output-ctx)
   :metrics       (:execution/metrics output-ctx)
   :timestamp     (java.time.Instant/now)})

(defn- make-exception-signal
  "Build a minimal failure signal when execution context is unavailable."
  [workflow exception]
  {:signal/type :workflow-failed
   :workflow-id (:workflow/id workflow)
   :error       (when exception (ex-message exception))
   :timestamp   (java.time.Instant/now)})

;------------------------------------------------------------------------------ Layer 0
;; Individual cleanup steps

(defn promote-mature-learnings!
  "Query promotable learnings from KB and auto-promote high-confidence ones.
   No-ops when knowledge-store is nil. Returns a vector of errors (may be empty)."
  [knowledge-store]
  (let [errors (volatile! [])]
    (try
      (when knowledge-store
        (let [promotable (knowledge/list-learnings knowledge-store {:promotable? true})]
          (doseq [learning promotable]
            (try
              (knowledge/promote-learning knowledge-store (:zettel/id learning) {})
              (catch Exception e
                (vswap! errors conj {:zettel/id (:zettel/id learning)
                                     :error (ex-message e)}))))))
      (catch Exception e
        (vswap! errors conj {:error (ex-message e)})))
    @errors))

(defn synthesize-patterns!
  "Detect recurring patterns in KB learnings. No-ops when knowledge-store is nil."
  [knowledge-store]
  (when knowledge-store
    (knowledge/synthesize-recurring-patterns! knowledge-store)))

(defn observe-workflow-signal!
  "Feed workflow completion/failure signal to the operator for pattern analysis."
  [opts output-ctx workflow exception]
  (when-let [observe-fn (:observe-signal-fn opts)]
    (let [signal (if output-ctx
                   (make-completion-signal output-ctx)
                   (make-exception-signal workflow exception))]
      (observe-fn signal))))

;------------------------------------------------------------------------------ Layer 1
;; Orchestrated cleanup

(defn- try-observe! [opts output-ctx workflow exception event-stream workflow-id]
  (try (observe-workflow-signal! opts output-ctx workflow exception)
       (catch Exception e
         (when event-stream
           (events/publish-event! event-stream (es/observer-signal-failed event-stream workflow-id e))))))

(defn- try-synthesize! [opts event-stream]
  (try (synthesize-patterns! (:knowledge-store opts))
       (catch Exception e
         (when event-stream
           (events/publish-event! event-stream (es/knowledge-synthesis-failed event-stream e))))))

(defn- try-promote! [opts event-stream]
  (let [errors (promote-mature-learnings! (:knowledge-store opts))]
    (doseq [err errors]
      (when event-stream
        (events/publish-event! event-stream
                        (es/knowledge-promotion-failed event-stream (ex-info (:error err) err)))))))

(defn- release-acquired-environments! [acquired-env]
  (when acquired-env
    (env/release-execution-environment! (:executor acquired-env)
                                        (:environment-id acquired-env))
    (env/release-execution-environment! (:worktree-executor acquired-env)
                                        (:worktree-environment-id acquired-env))))

(defn post-workflow-cleanup!
  "Post-workflow cleanup that fires on ALL exit paths.
   Each step is independently try-caught so one failure cannot mask another."
  [opts output-ctx workflow exception acquired-env]
  (let [event-stream (:event-stream opts)
        workflow-id  (get output-ctx :execution/id (:workflow/id workflow))]
    (try-observe! opts output-ctx workflow exception event-stream workflow-id)
    (try-synthesize! opts event-stream)
    (try-promote! opts event-stream)
    (release-acquired-environments! acquired-env)))
