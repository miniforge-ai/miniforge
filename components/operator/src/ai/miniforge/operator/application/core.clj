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
(ns ai.miniforge.operator.application.core
  "Lifecycle primitives shared by the intervention appliers — the bottom
   stratum of the D-3/D-3b application layer, split out of
   `application` so neither that namespace nor
   [[ai.miniforge.operator.application.verbs]] carries more than three
   real layers (rule 210).

   Two kinds of thing live here: the lifecycle publishers
   ([[advance!]], [[fail!]]) that turn a lifecycle step into a published
   state transition, and the raw mechanism effects ([[control-state-effect!]],
   [[safe-mode-effect!]]) that flip a runner flag or move the
   degradation manager and hand back the readback the verification step
   asserts. The appliers in `verbs` compose these; `application` calls
   [[advance!]] / [[fail!]] for the dispatch step and the terminal
   failure paths."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.consumer :as consumer]
   [ai.miniforge.operator.intervention :as intervention]
   [ai.miniforge.operator.messages :as messages]
   [ai.miniforge.reliability.interface :as reliability]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private failure-message-key-by-code
  {:application-error :application/application-error
   :control-state-readback-mismatch :application/control-state-readback-mismatch
   :missing-phase :application/missing-phase
   :no-degradation-manager :application/no-degradation-manager
   :invalid-policy-evaluation :application/invalid-policy-evaluation
   :no-live-runner :application/no-live-runner
   :no-policy-evaluator :application/no-policy-evaluator
   :no-resume-context :application/no-resume-context
   :no-resume-launcher :application/no-resume-launcher
   :not-implemented :application/not-implemented
   :policy-evaluation-readback-mismatch :application/policy-evaluation-readback-mismatch
   :resume-not-dispatched :application/resume-not-dispatched
   :resume-readback-mismatch :application/resume-readback-mismatch
   :safe-mode-readback-mismatch :application/safe-mode-readback-mismatch
   :unknown-phase :application/unknown-phase
   :unresolved-workflow-type :application/unresolved-workflow-type})

(def ^{:stratum 0} ^:private expected-degradation-mode-by-verb
  {:force-safe-mode :safe-mode
   :exit-safe-mode :nominal})

(defn- ^{:stratum 0} intervention-justification
  [interv]
  (if-some [justification (:intervention/justification interv)]
    justification
    (messages/t :application/default-justification)))

(defn- ^{:stratum 0} transition-succeeded?
  [result]
  (true? (:success? result)))

(defn ^{:stratum 0} control-state-effect!
  "Flip the control-state flag for `verb` and return the readback the
   verification step asserts."
  [control-state verb]
  (case verb
    :pause  (do (es/pause! control-state)
                {:verb :pause :observed (boolean (es/paused? control-state))
                 :expected true})
    :resume (do (es/resume! control-state)
                {:verb :resume :observed (boolean (es/paused? control-state))
                 :expected false})
    :cancel (do (es/cancel! control-state)
                {:verb :cancel :observed (boolean (es/cancelled? control-state))
                 :expected true})))

(defn ^{:stratum 0} resume-events-dir
  [launcher]
  (or (:events-dir launcher) (es/default-events-dir)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failure-message
  [reason-code]
  (messages/t (get failure-message-key-by-code
                   reason-code
                   :application/unknown-failure)))

(defn ^{:stratum 1} advance!
  "Apply lifecycle `step-fn` to `interv`, publish the transition, and
   return the updated intervention. Returns nil when the lifecycle step
   itself is rejected."
  [stream interv step-fn & step-args]
  (let [result (apply step-fn interv step-args)]
    (if (transition-succeeded? result)
      (let [updated (:intervention result)]
        (consumer/publish-state-changed! stream updated)
        updated)
      nil)))

(defn ^{:stratum 1} safe-mode-effect!
  "Move the degradation manager for `verb` and return the readback the
   verification step asserts."
  [manager verb interv]
  (let [justification (intervention-justification interv)]
    (case verb
      :force-safe-mode
      (reliability/enter-safe-mode! manager :manual justification)
      :exit-safe-mode
      (reliability/exit-safe-mode! manager
                                   justification
                                   (:intervention/requested-by interv)))
    {:verb verb
     :observed (reliability/degradation-mode manager)
     :expected (get expected-degradation-mode-by-verb verb)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} fail!
  "Stamp `reason-code` onto the intervention's failure details and
   publish the `:failed` transition. Returns the failed intervention, or
   nil when the lifecycle step is itself rejected."
  [stream interv reason-code]
  (let [with-failure-code (assoc-in interv
                                    [:intervention/details :failure/code]
                                    reason-code)]
    (when-let [failed (advance! stream
                                with-failure-code
                                intervention/fail
                                (failure-message reason-code))]
      failed)))
