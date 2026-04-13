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

(ns ai.miniforge.workflow.runner-events
  "Event publishing for workflow pipeline execution.

   Extracted from runner.clj for single-responsibility.
   All functions are safe to call with nil event-stream (no-op)."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.self-healing.interface :as self-healing]
   [ai.miniforge.workflow.messages :as messages]
   [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0
;; Core event dispatch

(defn- publish-via-websocket!
  "Send event JSON to a WebSocket connection."
  [event-stream event]
  (try
    (require '[org.httpkit.client :as http])
    (when-let [http-ns (find-ns 'org.httpkit.client)]
      (when-let [ws (:websocket event-stream)]
        (when-let [send-fn (ns-resolve http-ns 'send!)]
          (send-fn ws (json/generate-string event)))))
    (catch Exception e
      (println (messages/t :warn/websocket-send {:error (ex-message e)})))))

(defn publish-event!
  "Publish event to event stream or dashboard WebSocket.
   Safe to call with nil event-stream (no-op)."
  [event-stream event]
  (when event-stream
    (try
      (if (:websocket event-stream)
        (publish-via-websocket! event-stream event)
        (es/publish! event-stream event))
      (catch Exception e
        (println (messages/t :warn/publish-event {:error (ex-message e)}))))))

;------------------------------------------------------------------------------ Layer 1
;; Event construction helpers

(defn- build-completion-metrics
  "Extract non-nil metric fields from execution context."
  [context]
  (let [metrics (:execution/metrics context)]
    (cond-> {}
      (:tokens metrics)   (assoc :tokens (:tokens metrics))
      (:cost-usd metrics) (assoc :cost-usd (:cost-usd metrics))
      (:workflow/pr-info context) (assoc :pr-info (:workflow/pr-info context)))))

(defn- build-phase-event-data
  "Build event data map for a phase completion event."
  [result]
  (let [succeeded? (get result :success? (registry/succeeded-or-done? result))
        outcome    (if succeeded? :success :failure)
        duration-ms (get result :duration-ms
                      (get-in result [:phase/metrics :duration-ms]
                        (get-in result [:metrics :duration-ms])))
        error-info (when-not succeeded?
                     (or (:error result)
                         (when-let [msg (get-in result [:phase/gate-errors])]
                           {:message (messages/t :status/gate-failed) :details msg})
                         (when-let [msg (:message result)]
                           {:message msg})))
        tokens   (get-in result [:metrics :tokens]
                   (get-in result [:phase/metrics :tokens]))
        cost-usd (get-in result [:metrics :cost-usd]
                   (get-in result [:phase/metrics :cost-usd]))]
    (cond-> {:outcome outcome :duration-ms duration-ms}
      error-info    (assoc :error error-info)
      (:redirect-to result) (assoc :redirect-to (:redirect-to result))
      tokens        (assoc :tokens tokens)
      cost-usd      (assoc :cost-usd cost-usd))))

;------------------------------------------------------------------------------ Layer 1.5
;; Supervisory entity builders

(defn- workflow-run-fields
  "Entity fields for WorkflowRun projection, embedded in workflow lifecycle events.
   status is a keyword (:running/:completed/:failed); current-phase is a plain string."
  [context status current-phase]
  {:workflow-run/id            (:execution/id context)
   :workflow-run/workflow-key  (or (some-> (get-in context [:execution/workflow :workflow/id]) name) "")
   :workflow-run/intent        (or (get-in context [:execution/input :intent])
                                   (get-in context [:execution/input :task])
                                   "")
   :workflow-run/status        status
   :workflow-run/current-phase current-phase
   :workflow-run/started-at    (java.util.Date.)
   :workflow-run/updated-at    (java.util.Date.)
   :workflow-run/trigger-source (get-in context [:execution/opts :trigger-source] :cli)
   :workflow-run/correlation-id (:execution/id context)})

;------------------------------------------------------------------------------ Layer 2
;; Lifecycle event publishers

(defn publish-workflow-started!
  "Publish workflow started event."
  [event-stream context]
  (when event-stream
    (try
      (publish-event! event-stream
                      (merge (es/workflow-started event-stream
                                                  (:execution/id context)
                                                  (select-keys (:execution/workflow context)
                                                               [:workflow/id :workflow/version]))
                             (workflow-run-fields context :running "init")))
      (catch Exception e
        (println (messages/t :warn/publish-started {:error (ex-message e)}))))))

(defn publish-workflow-completed!
  "Publish workflow completed/failed event."
  [event-stream context]
  (when event-stream
    (try
      (let [wf-id        (:execution/id context)
            metrics-opts (build-completion-metrics context)
            last-phase   (or (some-> (:execution/current-phase context) name) "")]
        (if (registry/failed? context)
          (publish-event! event-stream
                          (merge (es/workflow-failed event-stream wf-id
                                                    {:message (messages/t :status/failed)
                                                     :errors (:execution/errors context)})
                                 (workflow-run-fields context :failed last-phase)))
          (publish-event! event-stream
                          (merge (es/workflow-completed event-stream wf-id
                                                       (:execution/status context)
                                                       (get-in context [:execution/metrics :duration-ms])
                                                       (when (seq metrics-opts) metrics-opts))
                                 (workflow-run-fields context :completed last-phase)))))
      (catch Exception e
        (println (messages/t :warn/publish-completed {:error (ex-message e)}))))))

(defn publish-phase-started!
  "Publish phase started event."
  [event-stream context phase-name]
  (when event-stream
    (try
      (publish-event! event-stream
                      (merge (es/phase-started event-stream (:execution/id context) phase-name
                                               {:phase/index (:execution/phase-index context)})
                             (workflow-run-fields context :running (name phase-name))))
      (catch Exception e
        (println (messages/t :warn/publish-phase-started {:error (ex-message e)}))))))

(defn publish-phase-completed!
  "Publish phase completed event."
  [event-stream context phase-name result]
  (when event-stream
    (try
      (publish-event! event-stream
                      (es/phase-completed event-stream (:execution/id context) phase-name
                                          (build-phase-event-data result)))
      (catch Exception e
        (println (messages/t :warn/publish-phase-completed {:error (ex-message e)}))))))

;------------------------------------------------------------------------------ Layer 2
;; Health check at phase boundary

(defn check-backend-health-at-boundary!
  "Check backend health at phase boundary. No-ops when :self-healing-config is absent.
   Returns switch-result or nil."
  [event-stream context]
  (when (get-in context [:execution/opts :self-healing-config])
    (try
      (let [backend (get-in context [:execution/opts :llm-backend :config :backend] :anthropic)
            sh-ctx {:llm {:backend backend}
                    :config (get-in context [:execution/opts :self-healing-config])}
            switch-result (self-healing/check-backend-health-and-switch sh-ctx)]
        (when (:switched? switch-result)
          (publish-event! event-stream (self-healing/emit-backend-switch-event sh-ctx switch-result))
          switch-result))
      (catch Exception e
        (println (messages/t :warn/health-check {:error (ex-message e)}))
        nil))))
