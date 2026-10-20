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
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.runner-callbacks :as callbacks]
            [ai.miniforge.workflow.runner-cleanup :as cleanup]
            [ai.miniforge.workflow.runner-defaults :as defaults]
            [ai.miniforge.workflow.runner-events :as events]
            [ai.miniforge.workflow.runner-execution-boundary :as execution-boundary]
            [ai.miniforge.workflow.runner-iteration :as iteration]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [ai.miniforge.workflow.runner-preparation :as preparation]
            [ai.miniforge.workflow.runner-start :as start]
            [ai.miniforge.workflow.runner-terminal :as terminal]
            [slingshot.slingshot :refer [try+]]))

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

(def ^{:stratum 0} ^:private acquire-environment preparation/acquire-environment)

(def ^{:stratum 0} ^:private wrap-phase-callbacks callbacks/wrap-phase-callbacks)

(def ^{:stratum 0} ^:private initial-context-anomaly->failed-context outcome/initial-context-anomaly->failed-context)

(def ^{:stratum 0} ^:private validate-completion outcome/validate-completion)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-pipeline
  "Execute a workflow pipeline.

   Pipeline: acquire environment -> build context -> admit start -> execute phases
           -> validate -> admit terminal publication -> checkpoint -> cleanup (always).

   Returns final execution context."
  ([workflow input]
   (run-pipeline workflow input {}))
  ([workflow input opts]
   (let [[acquired-env opts] (acquire-environment workflow input opts)
         pipeline            (build-pipeline workflow)
         max-phases          (get opts :max-phases (defaults/max-phases))
         control-state       (get opts :control-state
                                  (atom {:paused false :stopped false :adjustments {}}))
         event-stream        (:event-stream opts)
         callbacks           (wrap-phase-callbacks event-stream opts)
         skip-lifecycle?     (:skip-lifecycle-events opts)
         ctx-or-anomaly      (preparation/build-initial-context workflow input opts run-pipeline)
         initial-ctx         (if (anomaly/anomaly? ctx-or-anomaly)
                               (initial-context-anomaly->failed-context workflow input opts
                                                                        ctx-or-anomaly)
                               ctx-or-anomaly)
         output-ctx-vol      (volatile! nil)
         exception-vol       (volatile! nil)]

     (try+
       (let [started-ctx (start/admit! event-stream initial-ctx skip-lifecycle?)
             final-ctx (execution-boundary/execute-with-exception-handling
                         pipeline started-ctx callbacks control-state max-phases exception-vol)
             output-ctx (-> final-ctx validate-completion extract-output)]
         (terminal/finalize! event-stream output-ctx skip-lifecycle? output-ctx-vol))
      (finally
        (when-let [stop-heartbeats! (:stop-phase-heartbeats! callbacks)]
          (stop-heartbeats!))
        (cleanup/post-workflow-cleanup! opts @output-ctx-vol workflow
                                        @exception-vol acquired-env))))))

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
