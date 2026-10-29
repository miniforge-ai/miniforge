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

   The caller must invoke cleanup on every exit path.
   Every cleanup stage is attempted, preserving fatal failure priority.
   Extracted from runner.clj."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.resource-cleanup.interface :as resources]
   [ai.miniforge.workflow.boundary.cleanup-attempt :as attempt]
   [ai.miniforge.workflow.cleanup-knowledge :as knowledge]
   [ai.miniforge.workflow.cleanup-signals :as signals]
   [ai.miniforge.workflow.runner-environment :as env]
   [ai.miniforge.workflow.runner-events :as events]))

;------------------------------------------------------------------------------ Layer 0

;; Individual cleanup steps
(def ^{:stratum 0} promote-mature-learnings!
  "Query promotable learnings from KB and auto-promote high-confidence ones.
   No-ops when knowledge-store is nil. Returns a vector of errors (may be empty)."
  knowledge/promote-mature-learnings!)

(def ^{:stratum 0} synthesize-patterns!
  "Detect recurring patterns in KB learnings. No-ops when knowledge-store is nil."
  knowledge/synthesize-patterns!)

(defn ^{:stratum 0} observe-workflow-signal!
  "Feed workflow completion/failure signal to the operator for pattern analysis."
  [opts output-ctx workflow exception]
  (when-let [observe-fn (:observe-signal-fn opts)]
    (let [signal (if output-ctx
                   (signals/completion output-ctx)
                   (signals/failure workflow exception))]
      (observe-fn signal))))

(defn- ^{:stratum 0} observer-failure! [event-stream workflow-id error]
  (when event-stream
    (events/publish-event! event-stream (es/observer-signal-failed event-stream workflow-id error))))

(defn- ^{:stratum 0} synthesis-failure! [event-stream error]
  (when event-stream
    (events/publish-event! event-stream (es/knowledge-synthesis-failed event-stream error))))

(defn- ^{:stratum 0} release-acquired-environments! [acquired-env]
  (when acquired-env
    (resources/finish!
      (partial env/release-execution-environment! (:executor acquired-env)
               (:environment-id acquired-env))
      (partial env/release-execution-environment! (:worktree-executor acquired-env)
               (:worktree-environment-id acquired-env)))))

;------------------------------------------------------------------------------ Layer 1

;; Orchestrated cleanup
(defn- ^{:stratum 1} try-observe! [opts output-ctx workflow exception event-stream workflow-id]
  (attempt/attempt! (partial observe-workflow-signal! opts output-ctx workflow exception)
                    (partial observer-failure! event-stream workflow-id)))

(defn- ^{:stratum 1} try-synthesize! [opts event-stream]
  (attempt/attempt! (partial synthesize-patterns! (:knowledge-store opts))
                    (partial synthesis-failure! event-stream)))

(defn- ^{:stratum 1} try-promote! [opts event-stream]
  (let [errors (promote-mature-learnings! (:knowledge-store opts))]
    (doseq [err errors]
      (when event-stream
        (events/publish-event! event-stream
                        (es/knowledge-promotion-failed event-stream err))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} post-workflow-cleanup!
  "Post-workflow cleanup for the runner's exit path.
   Attempt every stage and both environments even when an earlier stage throws."
  [opts output-ctx workflow exception acquired-env]
  (let [event-stream (:event-stream opts)
        workflow-id  (get output-ctx :execution/id (:workflow/id workflow))
        observe (partial try-observe! opts output-ctx workflow exception event-stream workflow-id)
        synthesize (partial try-synthesize! opts event-stream)
        promote (partial try-promote! opts event-stream)
        release (partial release-acquired-environments! acquired-env)]
    (resources/finish! observe
      (partial resources/finish! synthesize
        (partial resources/finish! promote release)))))

(comment ::post-workflow-resource-lifetime)
