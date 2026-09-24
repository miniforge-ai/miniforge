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
   :policy-evaluation-refused :application/policy-evaluation-refused
   :resume-in-flight :application/resume-in-flight
   :resume-not-dispatched :application/resume-not-dispatched
   :resume-not-started :application/resume-not-started
   :resume-origin-unknown :application/resume-origin-unknown
   :resume-readback-mismatch :application/resume-readback-mismatch
   :resume-target-live :application/resume-target-live
   :safe-mode-readback-mismatch :application/safe-mode-readback-mismatch
   :unknown-phase :application/unknown-phase
   :unresolved-workflow-type :application/unresolved-workflow-type})

(def ^{:stratum 0} ^:private failure-detail-keys
  "What a mechanism may add to a failed intervention's details, beside
   `:failure/code`, so the operator sees why and where to look."
  [:failure/reason :failure/log :resume/run-id :resume/pid])

(defonce ^{:stratum 0} ^:private verification-pool
  ;; Work that waits on something slow — a launched run becoming
  ;; observable — runs here, off the consumer's pass and its lock.
  (atom nil))

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

(defn- ^{:stratum 0} new-verification-pool
  "A cached pool of named daemon threads: pending verification never
   keeps the process alive; the process owner drains it on the way out."
  ^java.util.concurrent.ExecutorService []
  (java.util.concurrent.Executors/newCachedThreadPool
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. ^Runnable runnable "miniforge-operator-verification")
         (.setDaemon true))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failure-message
  "The localized reason for `reason-code`, filled from its `details`."
  [reason-code details]
  (messages/t (get failure-message-key-by-code
                   reason-code
                   :application/unknown-failure)
              {:reason (some-> (:failure/reason details) name)
               :log (:failure/log details)
               :pid (:resume/pid details)}))

(defn ^{:stratum 1} anomaly-failure
  "`[code details]` for a mechanism's anomaly: the `:failure/code` it
   names when the lifecycle knows that code, else `default-code`, with
   the failure details it carries."
  [result default-code]
  (let [data (:anomaly/data result)
        code (:failure/code data)]
    [(if (contains? failure-message-key-by-code code) code default-code)
     (select-keys data failure-detail-keys)]))

(defn ^{:stratum 1} submit-verification!
  "Run `f` on the verification pool and return `result` — the
   intervention as it stands while `f` finishes it."
  [result f]
  (let [pool (swap! verification-pool #(or % (new-verification-pool)))]
    (.execute ^java.util.concurrent.ExecutorService pool ^Runnable f)
    result))

(defn ^{:stratum 1} stop-verifications!
  "Drain the verification pool like the consumer's poller (see
   `consumer/drain-executor!`); a later submission starts a new pool.
   Idempotent."
  []
  (when-let [pool (first (reset-vals! verification-pool nil))]
    (consumer/drain-executor! pool consumer/stop-drain-ms)))

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
  "Stamp `reason-code` (and any `details`, see [[failure-detail-keys]])
   onto the intervention's details and publish the `:failed` transition.
   Returns the failed intervention, or nil when the lifecycle step is
   itself rejected."
  ([stream interv reason-code]
   (fail! stream interv reason-code nil))
  ([stream interv reason-code details]
   (let [with-failure-code (update interv :intervention/details merge
                                   details {:failure/code reason-code})]
     (advance! stream
               with-failure-code
               intervention/fail
               (failure-message reason-code details)))))
