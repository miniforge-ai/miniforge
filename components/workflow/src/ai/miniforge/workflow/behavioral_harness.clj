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

(ns ai.miniforge.workflow.behavioral-harness
  "Behavioral harness orchestrator for workflow verification.

   Pure orchestration module (no LLM). Subscribes to the event stream during
   harness execution to collect telemetry, then evaluates the captured events
   through the :behavioral gate.

   Entry points:
     resolve-harness-config   - Reads :spec/behavioral-harness from ctx.
     run-behavioral-harness   - Subscribes, runs harness, collects events.
     evaluate-behavioral-gate - Builds telemetry artifact, calls gate check."
  (:require
   [clojure.java.shell :as shell]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.event-stream.interface :as event-stream]))

;;------------------------------------------------------------------------------ Layer 0: Context helpers

(defn- resolve-event-stream
  "Locate the event stream from execution context.
   Checks three canonical locations used across the workflow runtime."
  [ctx]
  (or (:event-stream ctx)
      (:execution/event-stream ctx)
      (get-in ctx [:execution/opts :event-stream])))

(defn- harness-subscriber-id
  "Generate a unique subscriber keyword for a harness observation window.
   Avoids collisions when multiple harness runs overlap."
  []
  (keyword (str "behavioral-harness-" (random-uuid))))

;;------------------------------------------------------------------------------ Layer 0: Public API

(defn resolve-harness-config
  "Read :spec/behavioral-harness from execution context.

   Returns nil when not declared — callers should skip behavioral verification
   in that case rather than treating it as an error.

   Arguments:
     ctx - Execution context map

   Returns:
     Harness config map, or nil"
  [ctx]
  (get ctx :spec/behavioral-harness))

;;------------------------------------------------------------------------------ Layer 1: Harness dispatch

(defn- execute-command-harness
  "Execute a :command harness by invoking a shell command.

   Expects :harness/command (string) and optionally :harness/args (vector).
   Working directory defaults to :execution/worktree-path from ctx, falling
   back to the JVM working directory.

   Returns anomaly data map on failure — never throws."
  [harness-config ctx]
  (let [command  (get harness-config :harness/command)
        args     (get harness-config :harness/args [])
        workdir  (or (get ctx :execution/worktree-path)
                     (System/getProperty "user.dir"))]
    (if (nil? command)
      {:status :error
       :error  {:type    :missing-command
                :message ":harness/command is required for :command harness type"
                :data    {:harness-config harness-config}}}
      (try
        (let [sh-args  (into [command] (concat args [:dir workdir]))
              result   (apply shell/sh sh-args)]
          (if (zero? (:exit result))
            {:status :ok
             :output (:out result)}
            {:status :error
             :error  {:type   :command-failed
                      :exit   (:exit result)
                      :stdout (:out result)
                      :stderr (:err result)}}))
        (catch Exception ex
          {:status :error
           :error  {:type    :execution-error
                    :message (ex-message ex)
                    :data    (ex-data ex)}})))))

(defn- execute-workflow-harness
  "Execute a :workflow harness via the execution context's generate-fn.

   The generate-fn is called with a merged map of :harness/input (from config)
   and the harness config itself, giving the callee full harness context.

   Returns anomaly data map on failure — never throws."
  [harness-config ctx]
  (let [generate-fn (get ctx :generate-fn)]
    (if (nil? generate-fn)
      {:status :error
       :error  {:type    :missing-generate-fn
                :message ":generate-fn is required on ctx for :workflow harness type"
                :data    {:harness-config harness-config}}}
      (try
        (let [harness-input (get harness-config :harness/input {})
              result        (generate-fn (merge harness-input
                                                {:harness/config harness-config}))]
          {:status :ok
           :output result})
        (catch Exception ex
          {:status :error
           :error  {:type    :execution-error
                    :message (ex-message ex)
                    :data    (ex-data ex)}})))))

(defn- dispatch-harness-execution
  "Route harness execution based on :harness/type.

   Supported types:
     :command  - Shell invocation via clojure.java.shell/sh
     :workflow - Workflow invocation via ctx :generate-fn

   Returns anomaly data map for unknown types — never throws."
  [harness-config ctx]
  (let [harness-type (get harness-config :harness/type)]
    (case harness-type
      :command  (execute-command-harness harness-config ctx)
      :workflow (execute-workflow-harness harness-config ctx)
      {:status :error
       :error  {:type         :unknown-harness-type
                :harness/type harness-type
                :message      (str "Unknown harness type: " (pr-str harness-type)
                                   ". Supported: :command, :workflow")}})))

;;------------------------------------------------------------------------------ Layer 1: Observation window

(defn run-behavioral-harness
  "Run a behavioral harness and collect events emitted during execution.

   Subscribes to the event stream before harness execution starts and
   unsubscribes after (always — even on error), providing a bounded
   observation window.  When event-stream is nil, the harness still runs
   but no events are collected.

   Arguments:
     harness-config - Harness configuration map (from resolve-harness-config)
     event-stream   - Event stream atom, or nil
     ctx            - Execution context

   Returns:
     {:behavioral/events         collected-events
      :behavioral/harness-config harness-config
      :behavioral/duration-ms    elapsed-ms
      :behavioral/status         :completed | :error
      :behavioral/error          anomaly-map}   ; only when :error"
  [harness-config event-stream ctx]
  (let [collected-events (atom [])
        subscriber-id    (harness-subscriber-id)
        start-ms         (System/currentTimeMillis)]
    ;; Open observation window — subscribe before harness starts
    (when event-stream
      (event-stream/subscribe!
       event-stream
       subscriber-id
       (fn [event]
         (swap! collected-events conj event))))
    (try
      (let [exec-result (dispatch-harness-execution harness-config ctx)
            elapsed-ms  (- (System/currentTimeMillis) start-ms)
            error?      (= :error (:status exec-result))]
        (cond-> {:behavioral/events         @collected-events
                 :behavioral/harness-config harness-config
                 :behavioral/duration-ms    elapsed-ms
                 :behavioral/status         (if error? :error :completed)}
          error? (assoc :behavioral/error (:error exec-result))))
      (catch Exception ex
        ;; Defensive catch — dispatch-harness-execution should not throw,
        ;; but we guard here so callers always receive data, not an exception.
        (let [elapsed-ms (- (System/currentTimeMillis) start-ms)]
          {:behavioral/events         @collected-events
           :behavioral/harness-config harness-config
           :behavioral/duration-ms    elapsed-ms
           :behavioral/status         :error
           :behavioral/error          {:type    :harness-orchestration-error
                                       :message (ex-message ex)
                                       :data    (ex-data ex)}}))
      (finally
        ;; Close observation window — always unsubscribe after harness finishes
        (when event-stream
          (event-stream/unsubscribe! event-stream subscriber-id))))))

;;------------------------------------------------------------------------------ Layer 2: Gate evaluation

(defn evaluate-behavioral-gate
  "Evaluate collected behavioral events through the :behavioral gate.

   Builds a telemetry artifact from the harness result and delegates
   to gate/check-gate :behavioral.  The gate implementation is responsible
   for interpreting event semantics; this function only prepares the artifact.

   Arguments:
     harness-result - Result map from run-behavioral-harness
     ctx            - Execution context (forwarded to gate)

   Returns:
     Gate result: {:passed? bool :errors [...] :gate :behavioral}"
  [harness-result ctx]
  (let [events     (get harness-result :behavioral/events [])
        config     (get harness-result :behavioral/harness-config)
        elapsed-ms (get harness-result :behavioral/duration-ms 0)
        artifact   {:telemetry/events       events
                    :telemetry/event-count  (count events)
                    :telemetry/duration-ms  elapsed-ms
                    :telemetry/harness-config config
                    :behavioral/status      (get harness-result :behavioral/status)}]
    (gate/check-gate :behavioral artifact ctx)))

;;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Resolve harness config from execution context
  (resolve-harness-config
   {:spec/behavioral-harness {:harness/type    :command
                              :harness/command "echo"
                              :harness/args    ["hello"]}})
  ;; => {:harness/type :command :harness/command "echo" :harness/args ["hello"]}

  ;; Returns nil when not configured — callers skip behavioral verification
  (resolve-harness-config {})
  ;; => nil

  ;; Run a command harness with an event stream
  (let [stream (event-stream/create-event-stream {:sinks []})
        config {:harness/type    :command
                :harness/command "echo"
                :harness/args    ["behavioral harness ran"]}]
    (run-behavioral-harness config stream {}))
  ;; => {:behavioral/events         []
  ;;     :behavioral/harness-config {:harness/type :command ...}
  ;;     :behavioral/duration-ms    <N>
  ;;     :behavioral/status         :completed}

  ;; Run without event stream (nil)
  (run-behavioral-harness
   {:harness/type :command :harness/command "date"}
   nil
   {})

  :leave-this-here)
