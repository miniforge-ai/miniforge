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

(ns ai.miniforge.phase.interface
  "Phase interceptor registry interface.

   Phases are interceptors with :enter, :leave, and :error functions.
   Each phase type registers via defmethod, providing defaults and behavior.

   Usage:
     (get-phase-interceptor {:phase :implement})
     (get-phase-interceptor {:phase :implement :budget {:tokens 50000}})

   Interceptor structure (Pedestal-style):
     {:name    keyword?
      :enter   (fn [ctx] -> ctx)    ; Execute phase
      :leave   (fn [ctx] -> ctx)    ; Post-processing
      :error   (fn [ctx ex] -> ctx) ; Error handling/repair}"
  (:require
   [ai.miniforge.phase.agent-behavior :as agent-behavior]
   [ai.miniforge.phase.file-context :as file-context]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.phase.phase-result :as phase-result]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.phase.telemetry :as telemetry]))

;------------------------------------------------------------------------------ Layer 0
;; Phase-result pass-throughs

(def enter-context
  "Build canonical phase result context."
  phase-result/enter-context)

(def exception-error
  "Build error map from exception."
  phase-result/exception-error)

;------------------------------------------------------------------------------ Layer 0
;; Re-export registry functions

(defn ensure-phase-implementations-loaded!
  "Ensure configured phase implementations have been required."
  []
  (loader/ensure-phase-implementations-loaded!))

(def configured-phase-namespaces
  "Return the configured phase implementation namespaces discovered from resources."
  loader/configured-phase-namespaces)

(def reset-phase-loader!
  "Reset phase implementation loader state for tests."
  loader/reset-loader!)

(defn get-phase-interceptor
  "Get interceptor for a phase configuration.

   Arguments:
     config - Map with :phase keyword and optional overrides
              {:phase :implement :budget {:tokens 50000}}

   Returns:
     Interceptor map with :name, :enter, :leave, :error"
  [config]
  (ensure-phase-implementations-loaded!)
  (registry/get-phase-interceptor config))

(defn list-phases
  "List all registered phase types.

   Returns:
     Set of phase keywords"
  []
  (ensure-phase-implementations-loaded!)
  (registry/list-phases))

(defn phase-defaults
  "Get default configuration for a phase type.

   Arguments:
     phase-kw - Phase keyword like :plan, :implement

   Returns:
     Default config map or nil if unknown"
  [phase-kw]
  (ensure-phase-implementations-loaded!)
  (registry/phase-defaults phase-kw))

(def merge-with-defaults
  "Merge user config with phase defaults."
  registry/merge-with-defaults)

(def register-phase-defaults!
  "Register default configuration for a phase type."
  registry/register-phase-defaults!)

(def get-phase-interceptor-method
  "The raw multimethod for registering phase interceptor implementations.
   Use defmethod on this for new phase types."
  registry/get-phase-interceptor)

(def extract-status
  "Extract a status keyword from a map by trying known status keys."
  registry/extract-status)

(def succeeded?
  "Check if a result map indicates success."
  registry/succeeded?)

(def failed?
  "Check if a result map indicates failure."
  registry/failed?)

(def already-done?
  "Check if a result map indicates work was already complete (neutral outcome)."
  registry/already-done?)

(def succeeded-or-done?
  "Check if a result map indicates success or already-done (neutral)."
  registry/succeeded-or-done?)

(def retrying?
  "Check if a result map indicates retry."
  registry/retrying?)

(def transition-request
  "Return the transition request attached to a phase result, if any."
  phase-result/transition-request)

(def transition-target
  "Return the requested transition target phase, if any."
  phase-result/transition-target)

(def redirect-requested?
  "True when a phase result requests a redirect transition."
  phase-result/redirect-requested?)

(def create-streaming-callback
  "Create a phase-scoped streaming callback when an event stream is available."
  telemetry/create-streaming-callback)

(def resolve-event-stream
  "Locate the event-stream atom from an execution context.
   Checks :event-stream and :execution/event-stream keys."
  telemetry/resolve-event-stream)

(def emit-phase-started!
  "Emit a :workflow/phase-started event. Safe no-op when event-stream is absent."
  telemetry/emit-phase-started!)

(def emit-phase-completed!
  "Emit a :workflow/phase-completed event. Safe no-op when event-stream is absent."
  telemetry/emit-phase-completed!)

(def create-streaming-callback-or-noop
  "Create a phase-scoped streaming callback, or no-op if unavailable.
   Never returns nil — always returns a callable function."
  telemetry/create-streaming-callback-or-noop)

(def emit-agent-started!
  "Emit an :agent/started event when a phase begins agent execution."
  telemetry/emit-agent-started!)

(def emit-agent-completed!
  "Emit an :agent/completed event when a phase finishes agent execution."
  telemetry/emit-agent-completed!)

(def emit-milestone-started!
  "Emit a :phase/milestone-started event when a milestone checkpoint begins.
   Called automatically from emit-phase-started!; also available for direct use."
  telemetry/emit-milestone-started!)

(def emit-milestone-completed!
  "Emit a :phase/milestone-completed event when a milestone checkpoint succeeds.
   Called automatically from emit-phase-completed! on :success outcome."
  telemetry/emit-milestone-completed!)

(def emit-milestone-failed!
  "Emit a :phase/milestone-failed event when a milestone checkpoint fails.
   Called automatically from emit-phase-completed! on non-:success outcome."
  telemetry/emit-milestone-failed!)

(def determine-phase-status
  "Resolve the outcome status for a phase from its result and gate outputs."
  registry/determine-phase-status)

(def load-files-in-scope
  "Load file contents for a phase's configured in-scope paths."
  file-context/load-files-in-scope)

(def load-and-filter-behaviors
  "Load and filter rule-pack agent-behavior entries applicable to a phase."
  agent-behavior/load-and-filter-behaviors)

(def success
  "Build a successful environment-model phase result."
  phase-result/success)

(def error
  "Build an error environment-model phase result."
  phase-result/error)

(def skipped
  "Build a skipped-phase result for phases that short-circuit."
  phase-result/skipped)

(def fail-phase
  "Mark a phase state as failed and attach the error map."
  phase-result/fail-phase)

(def request-redirect
  "Attach a redirect transition request to a phase result."
  phase-result/request-redirect)

(def fail-and-request-redirect
  "Mark a phase state as failed and request a redirect transition."
  phase-result/fail-and-request-redirect)

(def test-metrics
  "Build a standard test-phase metrics map."
  phase-result/test-metrics)

(def result-succeeded?
  "True when a phase result map has :status :success.
   Distinct from `succeeded?` which inspects multiple status keys across
   execution/step/chain shapes."
  phase-result/succeeded?)

;------------------------------------------------------------------------------ Layer 1
;; Pipeline construction

(defn build-pipeline
  "Build interceptor pipeline from workflow config.

   Arguments:
     workflow - Workflow map with :workflow/pipeline vector

   Returns:
     Vector of interceptor maps"
  [workflow]
  (ensure-phase-implementations-loaded!)
  (mapv get-phase-interceptor (:workflow/pipeline workflow)))

(defn validate-pipeline
  "Validate a workflow pipeline configuration.

   Arguments:
     workflow - Workflow map

   Returns:
     {:valid? bool :errors [...] :warnings [...]}"
  [workflow]
  (ensure-phase-implementations-loaded!)
  (let [pipeline (:workflow/pipeline workflow)
        known-phases (list-phases)
        errors (atom [])
        warnings (atom [])]

    (when (empty? pipeline)
      (swap! errors conj {:error :empty-pipeline
                          :message "Workflow pipeline is empty"}))

    (doseq [{:keys [phase on-fail on-success] :as _config} pipeline]
      (when-not (contains? known-phases phase)
        (swap! errors conj {:error :unknown-phase
                            :phase phase
                            :message (str "Unknown phase: " phase)}))

      (when (and on-fail (not (contains? known-phases on-fail)))
        (swap! warnings conj {:warning :unknown-on-fail-target
                              :phase phase
                              :target on-fail}))

      (when (and on-success (not (contains? known-phases on-success)))
        (swap! warnings conj {:warning :unknown-on-success-target
                              :phase phase
                              :target on-success})))

    {:valid? (empty? @errors)
     :errors @errors
     :warnings @warnings}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; List available phases
  (list-phases)
  ;; => #{:plan :implement :verify :review :release :done}

  ;; Get defaults for a phase
  (phase-defaults :implement)
  ;; => {:agent :implementer :gates [:syntax :lint] :budget {...}}

  ;; Get interceptor with defaults
  (get-phase-interceptor {:phase :plan})

  ;; Get interceptor with overrides
  (get-phase-interceptor {:phase :implement
                          :budget {:tokens 50000}
                          :gates [:syntax :lint :no-secrets]})

  ;; Build pipeline from workflow
  (def workflow {:workflow/id :test
                 :workflow/pipeline [{:phase :plan}
                                     {:phase :implement}
                                     {:phase :done}]})
  (build-pipeline workflow)

  ;; Validate pipeline
  (validate-pipeline workflow)

  :leave-this-here)
