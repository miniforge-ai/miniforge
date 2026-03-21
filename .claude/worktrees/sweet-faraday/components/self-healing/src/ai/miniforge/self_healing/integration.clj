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

(ns ai.miniforge.self-healing.integration
  "Integration helpers for self-healing in workflow execution.

   Provides wrappers for backend health tracking and workaround application."
  (:require
   [ai.miniforge.self-healing.backend-health :as health]
   [ai.miniforge.self-healing.workaround-detector :as detector]))

;;------------------------------------------------------------------------------ Layer 0
;; Configuration helpers

(defn get-self-healing-config
  "Extract self-healing configuration from context or use defaults.

   Arguments:
     context - Execution context map

   Returns: Map with :enabled, :auto-apply, :auto-switch, :threshold, :cooldown-ms"
  [context]
  (let [config (get-in context [:config :self-healing])]
    {:enabled (get config :enabled true)
     :auto-apply (get config :workaround-auto-apply true)
     :auto-switch (get config :backend-auto-switch true)
     :threshold (get config :backend-health-threshold 0.90)
     :cooldown-ms (get config :backend-switch-cooldown-ms 1800000)}))

(defn get-current-backend
  "Get current backend from context or config.

   Arguments:
     context - Execution context map

   Returns: Keyword backend name"
  [context]
  (or (get-in context [:llm :backend])
      (get-in context [:config :llm :backend])
      :anthropic))

;;------------------------------------------------------------------------------ Layer 1
;; Backend health tracking

(defn execute-with-health-tracking
  "Execute operation with backend health tracking.

   Automatically records success/failure and checks for backend switches.
   On error, attempts to apply workarounds and retry.

   Arguments:
     context - Execution context map
     operation-fn - Function to execute (no args, returns result)

   Returns: Map with :success?, :result or :error, :workaround-applied?, :backend-switched?"
  [context operation-fn]
  (let [config (get-self-healing-config context)
        backend (get-current-backend context)]

    (if-not (:enabled config)
      ;; Self-healing disabled - execute normally
      (try
        {:success? true
         :result (operation-fn)}
        (catch Exception e
          {:success? false
           :error e}))

      ;; Self-healing enabled - track health and apply workarounds
      (try
        (let [result (operation-fn)]
          ;; Record successful call
          (health/record-backend-call! backend true)
          {:success? true
           :result result
           :workaround-applied? false
           :backend-switched? false})

        (catch Exception e
          ;; Record failed call
          (health/record-backend-call! backend false)

          ;; Try to apply workaround if enabled
          (let [workaround-result (when (:auto-apply config)
                                   (detector/detect-and-apply-workaround e))
                should-retry? (and (:workaround-found? workaround-result)
                                 (:applied? workaround-result)
                                 (:success? workaround-result))]

            (if should-retry?
              ;; Workaround applied successfully - retry operation
              (try
                (let [retry-result (operation-fn)]
                  (health/record-backend-call! backend true)
                  {:success? true
                   :result retry-result
                   :workaround-applied? true
                   :workaround-pattern (:pattern workaround-result)})
                (catch Exception retry-error
                  ;; Retry also failed
                  (health/record-backend-call! backend false)
                  {:success? false
                   :error retry-error
                   :workaround-applied? true
                   :workaround-failed? true}))

              ;; No workaround or workaround failed - return error
              {:success? false
               :error e
               :workaround-applied? false
               :workaround-found? (:workaround-found? workaround-result)})))))))

(defn check-backend-health-and-switch
  "Check backend health and switch if necessary.

   Should be called at phase boundaries or after failures.

   Arguments:
     context - Execution context map

   Returns: Map with :switched?, :from, :to, :reason or nil if no switch"
  [context]
  (let [config (get-self-healing-config context)
        backend (get-current-backend context)]

    (when (and (:enabled config)
               (:auto-switch config))
      (let [switch-result (health/check-and-switch-if-needed
                          backend
                          (:threshold config)
                          (:cooldown-ms config))]
        (when switch-result
          (assoc switch-result :switched? true))))))

;;------------------------------------------------------------------------------ Layer 2
;; Event emission helpers

(defn emit-workaround-event
  "Emit workaround-applied event to event stream.

   Arguments:
     context - Execution context map
     workaround-result - Result from workaround application

   Returns: Event map"
  [context workaround-result]
  (let [pattern (:pattern workaround-result)]
    {:event/type :self-healing/workaround-applied
     :event/id (java.util.UUID/randomUUID)
     :event/timestamp (java.time.Instant/now)
     :event/version "1.0.0"
     :event/sequence-number (or (get-in context [:execution/sequence-number]) 0)
     :workflow/id (or (:workflow/id context) (java.util.UUID/randomUUID))
     :pattern-id (:id pattern)
     :success? (:success? workaround-result)
     :message (format "Applied workaround: %s" (:description pattern))}))

(defn emit-backend-switch-event
  "Emit backend-switched event to event stream.

   Arguments:
     context - Execution context map
     switch-result - Result from backend switch

   Returns: Event map"
  [context switch-result]
  {:event/type :self-healing/backend-switched
   :event/id (java.util.UUID/randomUUID)
   :event/timestamp (java.time.Instant/now)
   :event/version "1.0.0"
   :event/sequence-number (or (get-in context [:execution/sequence-number]) 0)
   :workflow/id (or (:workflow/id context) (java.util.UUID/randomUUID))
   :from (:from switch-result)
   :to (:to switch-result)
   :reason (str "Backend health below threshold")
   :cooldown-until (:cooldown-until switch-result)
   :message (str "Switched backend from " (name (:from switch-result))
                " to " (name (:to switch-result))
                " due to low success rate")})

;;------------------------------------------------------------------------------ Layer 3
;; High-level integration API

(defn wrap-phase-execution
  "Wrap phase execution with self-healing capabilities.

   This is the main integration point for workflow execution.
   Wraps phase execution to:
   1. Track backend health
   2. Apply workarounds on errors
   3. Check and switch backends if needed
   4. Emit events

   Arguments:
     context - Execution context map
     phase-fn - Phase execution function
     emit-event-fn - Function to emit events (optional)

   Returns: Updated context with execution result"
  ([context phase-fn]
   (wrap-phase-execution context phase-fn nil))
  ([context phase-fn emit-event-fn]
   (let [;; Execute phase with health tracking
         exec-result (execute-with-health-tracking context phase-fn)

         ;; Emit workaround event if applied
         _ (when (and (:workaround-applied? exec-result) emit-event-fn)
             (emit-event-fn (emit-workaround-event context exec-result)))

         ;; Check if backend switch needed
         switch-result (check-backend-health-and-switch context)

         ;; Emit backend switch event if switched
         _ (when (and switch-result emit-event-fn)
             (emit-event-fn (emit-backend-switch-event context switch-result)))

         ;; Update context with results
         updated-context (-> context
                            (assoc :self-healing/last-exec-result exec-result)
                            (assoc :self-healing/last-switch-result switch-result))]

     (if (:success? exec-result)
       (assoc updated-context :phase/result (:result exec-result))
       (assoc updated-context :phase/error (:error exec-result))))))
