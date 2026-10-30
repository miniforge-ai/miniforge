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
(ns ai.miniforge.workflow.runner
  "Compose workflow setup, bounded execution, lifecycle publication, and cleanup."
  (:require [ai.miniforge.workflow.boundary.runner-lifetime :as lifetime]
            [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
            [ai.miniforge.workflow.run-owner-lock :as owner]
            [ai.miniforge.workflow.runner-callbacks :as callbacks]
            [ai.miniforge.workflow.runner-cleanup :as cleanup]
            [ai.miniforge.workflow.runner-events :as events]
            [ai.miniforge.workflow.runner-iteration :as iteration]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [ai.miniforge.workflow.runner-preparation :as preparation]
            [ai.miniforge.workflow.runner-session :as session]))

;------------------------------------------------------------------------------ Layer 0

;; Preserve the public runner API while implementation dependencies flow downward.
(def ^{:stratum 0} publish-event! events/publish-event!)

(def ^{:stratum 0} publish-workflow-started! events/publish-workflow-started!)

(def ^{:stratum 0} publish-workflow-completed! events/publish-workflow-completed!)

(def ^{:stratum 0} publish-phase-started! events/publish-phase-started!)

(def ^{:stratum 0} publish-phase-completed! events/publish-phase-completed!)

(def ^{:stratum 0} promote-mature-learnings! cleanup/promote-mature-learnings!)

(def ^{:stratum 0} build-pipeline preparation/build-pipeline)

(def ^{:stratum 0} validate-pipeline preparation/validate-pipeline)

(def ^{:stratum 0} handle-empty-pipeline outcome/handle-empty-pipeline)

(def ^{:stratum 0} handle-max-phases-exceeded outcome/handle-max-phases-exceeded)

(def ^{:stratum 0} handle-phase-retry-exhausted outcome/handle-phase-retry-exhausted)

(def ^{:stratum 0} terminal-state? outcome/terminal-state?)

(def ^{:stratum 0} execute-single-iteration iteration/execute-single-iteration)

(def ^{:stratum 0} extract-output outcome/extract-output)

(defn- ^{:stratum 0} effective-run-id
  "The id the run executes under, settled before it starts so the owner
   lock and the context share it: a restored snapshot's, else the caller's
   uuid :workflow-id (same parsing as context/opts-run-id), else a fresh
   one."
  [opts]
  (let [wid (:workflow-id opts)]
    (or (get-in opts [:resume-machine-snapshot :execution/id])
        (cond
          (uuid? wid)   wid
          (string? wid) (parse-uuid wid))
        (random-uuid))))

(def ^{:stratum 0} ^:private acquire-environment preparation/acquire-environment)

(def ^{:stratum 0} ^:private wrap-phase-callbacks callbacks/wrap-phase-callbacks)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-pipeline
  "Execute a workflow pipeline.

   Pipeline: acquire environment -> build context -> admit start -> execute phases
           -> validate -> admit terminal publication -> checkpoint -> cleanup (always).

   Returns final execution context."
  ([workflow input]
   (run-pipeline workflow input {}))
  ([workflow input opts]
   ;; One live process per run id, held for the whole run: a resume or a
   ;; launcher retry of a run still running is refused (the owner lock's
   ;; anomaly is thrown) before anything is acquired. The root is the one
   ;; the run checkpoints under: the snapshot's when the context is
   ;; restored (snapshot and phase results both given), else the options'.
   (let [run-id (effective-run-id opts)
         restored? (and (:resume-machine-snapshot opts) (:resume-phase-results opts))]
     (owner/with-run-owner
      (checkpoint-paths/resolve-checkpoint-root (cond-> opts
                                                  restored? (merge (:resume-machine-snapshot opts))))
      run-id
      (fn []
        (let [[acquired-env opts] (acquire-environment workflow input (assoc opts :workflow-id run-id))
              owned (session/create (wrap-phase-callbacks (:event-stream opts) opts)
                                    build-pipeline extract-output)
              execute (partial session/execute! workflow input opts owned run-pipeline)
              release (partial lifetime/release! opts workflow acquired-env owned)]
          (lifetime/protect! owned execute release)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} build-initial-context
  "Build the initial execution context from workflow, input, and opts.

   Returns the assembled execution context on success, or an
   `:invalid-input` anomaly when `:governed` mode is requested but no
   capsule executor + `:environment-id` were supplied in `opts`. Governed
   mode forbids worktree fallback (N11 §7.4).

   Supplies the public runner as an explicit execution port; context
   preparation never depends back on this namespace."
  [workflow input opts]
  (preparation/build-initial-context workflow input opts run-pipeline))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.phase-software-factory.plan])
  (require '[ai.miniforge.phase-software-factory.implement])

  (def simple-workflow
    {:workflow/id :simple-test
     :workflow/version "2.0.0"
     :workflow/pipeline
     [{:phase :plan}
      {:phase :implement}
      {:phase :done}]})

  (build-pipeline simple-workflow)
  (validate-pipeline simple-workflow)

  :leave-this-here)
