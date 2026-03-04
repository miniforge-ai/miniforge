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

(ns ai.miniforge.phase.implement
  "Implementation phase interceptor.

   Generates code artifacts from plans.
   Agent: :implementer
   Default gates: [:syntax :lint]"
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.phase.file-context :as file-ctx]
            [ai.miniforge.phase.agent-behavior :as agent-beh]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Event stream helpers (optional dependency)

(defn- emit-phase-started!
  "Emit phase-started event if event-stream is available in context."
  [ctx phase]
  (when-let [event-stream (:event-stream ctx)]
    (when-let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
      (when-let [phase-started (requiring-resolve 'ai.miniforge.event-stream.interface/phase-started)]
        (let [workflow-id (:execution/id ctx)]
          (publish! event-stream (phase-started event-stream workflow-id phase)))))))

(defn- emit-phase-completed!
  "Emit phase-completed event if event-stream is available in context."
  [ctx phase _result]
  (when-let [event-stream (:event-stream ctx)]
    (when-let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
      (when-let [phase-completed (requiring-resolve 'ai.miniforge.event-stream.interface/phase-completed)]
        (let [workflow-id (:execution/id ctx)
              outcome (if (registry/succeeded-or-done? (:phase ctx)) :success :failure)
              duration-ms (get-in ctx [:phase :duration-ms])]
          (publish! event-stream (phase-completed event-stream workflow-id phase
                                                   {:outcome outcome :duration-ms duration-ms})))))))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  {:agent :implementer
   :gates [:syntax :lint]
   :budget {:tokens 30000
            :iterations 5
            :time-seconds 600}
   ;; Implementation is code-heavy - hint at Sonnet
   :model-hint :sonnet-4.5})

;; Register defaults on load
(registry/register-phase-defaults! :implement default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- build-implement-task
  "Build the task map for the implementer agent from execution context."
  [ctx]
  (let [input (get-in ctx [:execution/input])
        plan-result (get-in ctx [:execution/phase-results :plan :result :output])
        verify-failure (get-in ctx [:execution/phase-results :verify])
        worktree-path (or (:worktree-path ctx) (System/getProperty "user.dir"))
        files-in-scope (or (get-in input [:context :files-in-scope])
                           (get-in input [:intent :scope]))
        existing-files (file-ctx/load-files-in-scope worktree-path files-in-scope)
        behavior-addendum (agent-beh/load-and-filter-behaviors
                            :implement {:task {:task/intent (:intent input)}})]
    (let [base-task {:task/id (random-uuid)
                     :task/type :implement
                     :task/description (:description input)
                     :task/title (:title input)
                     :task/intent (:intent input)
                     :task/constraints (:constraints input)
                     :task/plan plan-result
                     :task/existing-files existing-files
                     :task/behavior-addendum behavior-addendum}]
      (cond-> base-task
        verify-failure
        (assoc :task/verify-failures
               {:test-results (get-in verify-failure [:result :output :metadata :test-results])
                :test-output (get-in verify-failure [:result :output :metadata :test-results :output])})))))

(defn- create-streaming-callback
  "Create a streaming callback for agent output if event-stream is available."
  [ctx]
  (when-let [es (:event-stream ctx)]
    (when-let [create-cb (requiring-resolve
                           'ai.miniforge.event-stream.interface/create-streaming-callback)]
      (create-cb es (:execution/id ctx) :implement
                 {:print? (not (:quiet ctx)) :quiet? (:quiet ctx)}))))

(defn- collect-peer-advice
  "Collect peer messages for the implementer agent if a message router is available."
  [ctx]
  (when-let [msg-router (:message-router ctx)]
    (try
      (let [get-msgs (requiring-resolve
                       'ai.miniforge.agent.interface.protocols.messaging/get-messages-for-agent)
            msgs (get-msgs msg-router :implementer (:execution/id ctx))]
        (when (seq msgs)
          {:peer-messages (vec msgs)}))
      (catch Exception _e nil))))

(defn- enter-implement
  "Execute implementation phase.

   Reads plan from context, invokes implementer agent,
   runs through inner loop with syntax/lint gates."
  [ctx]
  (emit-phase-started! ctx :implement)
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)
        implementer-agent (agent/create-implementer {})
        task (build-implement-task ctx)
        on-chunk (create-streaming-callback ctx)
        agent-ctx (cond-> ctx on-chunk (assoc :on-chunk on-chunk))
        peer-advice (collect-peer-advice ctx)
        task (cond-> task
               peer-advice (assoc :task/peer-advice peer-advice))
        result (try
                 (agent/invoke implementer-agent task agent-ctx)
                 (catch Exception e
                   (response/failure e)))]
    (-> ctx
        (assoc-in [:phase :name] :implement)
        (assoc-in [:phase :agent] :implementer)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running)
        (assoc-in [:phase :result] result))))

(defn- leave-implement
  "Post-processing for implementation phase.

   Records metrics, captures code artifacts."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        agent-status (:status result)
        iterations (get-in ctx [:phase :iterations] 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        phase-status (cond
                       (= :already-implemented agent-status) :already-implemented
                       :else (registry/determine-phase-status
                               agent-status iterations max-iterations))
        metrics (get result :metrics {:tokens 0 :duration-ms duration-ms})
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] phase-status)
                        (assoc-in [:phase :metrics] metrics)
                        (assoc-in [:metrics :implementation :duration-ms] duration-ms)
                        (assoc-in [:metrics :implementation :repair-cycles] (dec iterations))
                        (update-in [:execution :phases-completed] (fnil conj []) :implement)
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Warn if result has no output and isn't already-implemented
    (when (and (not= :already-implemented agent-status)
               (nil? (:output result)))
      (println "WARNING: implement phase result has no :output — artifact may be nil"))
    ;; Emit phase completed event
    (emit-phase-completed! updated-ctx :implement result)
    ;; Handle retrying: increment iteration counter and record last error
    (cond-> updated-ctx
      (registry/retrying? (:phase updated-ctx))
      (-> (update-in [:phase :iterations] (fnil inc 1))
          (assoc-in [:phase :last-error]
                    (or (get-in result [:error :message])
                        (get-in result [:output :error])
                        "Agent returned error status")))

      (= :already-implemented agent-status)
      (assoc-in [:phase :skipped-reason] :already-implemented))))

(defn- error-implement
  "Handle implementation phase errors.

   Attempts repair via inner loop if within budget."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 5)
        on-fail (get-in ctx [:phase-config :on-fail])]
    (cond
      ;; Within budget - retry
      (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))

      ;; Has on-fail transition - redirect
      on-fail
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :redirect-to] on-fail)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)}))

      ;; No recovery - propagate
      :else
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)})))))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod registry/get-phase-interceptor :implement
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::implement
     :config merged
     :enter (fn [ctx]
              (enter-implement (assoc ctx :phase-config merged)))
     :leave leave-implement
     :error error-implement}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get implement interceptor with defaults
  (registry/get-phase-interceptor {:phase :implement})

  ;; Get with overrides
  (registry/get-phase-interceptor {:phase :implement
                                   :budget {:tokens 50000}
                                   :gates [:syntax :lint :no-secrets]})

  ;; Check defaults
  (registry/phase-defaults :implement)

  :leave-this-here)
