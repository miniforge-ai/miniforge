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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.self-healing.interface :as self-healing]
   [ai.miniforge.workflow.boundary.runner-heartbeat :as heartbeat]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.runner-event-dispatch :as dispatch]
   [ai.miniforge.workflow.runner-failure-event :as failure-event]
   [ai.miniforge.workflow.runner-phase-event :as phase-event]
   [ai.miniforge.workflow.runner-pr-events :as pr-events]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

;; Core event dispatch
(def ^{:stratum 0} publish-event! dispatch/publish-event!)

;; Event construction helpers
(defn- ^{:stratum 0} build-completion-metrics
  "Extract non-nil metric fields from execution context."
  [context]
  (let [metrics (:execution/metrics context)]
    (cond-> {}
      (:tokens metrics)   (assoc :tokens (:tokens metrics))
      (:cost-usd metrics) (assoc :cost-usd (:cost-usd metrics))
      (:workflow/pr-info context) (assoc :pr-info (:workflow/pr-info context))
      (seq (:execution/dag-pr-infos context)) (assoc :pr-infos (:execution/dag-pr-infos context))
      (:workflow/evidence-bundle-id context)
      (assoc :workflow/evidence-bundle-id (:workflow/evidence-bundle-id context)))))

;; Supervisory entity builders
(def ^{:stratum 0} ^:private initial-phase "init")

(defn- ^{:stratum 0} workflow-run-fields
  "Entity fields for WorkflowRun projection, embedded in workflow lifecycle events.
   status is a keyword (:running/:completed/:failed); current-phase is a plain string."
  [context status current-phase]
  (let [now (java.util.Date.)]
    {:workflow-run/id            (:execution/id context)
     :workflow-run/workflow-key  (or (some-> (get-in context [:execution/workflow :workflow/id]) name) "")
     :workflow-run/intent        (or (get-in context [:execution/input :intent])
                                     (get-in context [:execution/input :task])
                                     "")
     :workflow-run/status        status
     :workflow-run/current-phase current-phase
     :workflow-run/started-at    now
     :workflow-run/updated-at    now
     :workflow-run/trigger-source (get-in context [:execution/opts :trigger-source] :cli)
     ;; An inherited id (threaded by run-chain across all steps of one ETL
     ;; invocation) wins; a standalone run correlates to its own execution.
     :workflow-run/correlation-id (or (:workflow-run/correlation-id context)
                                      (:execution/id context))}))

;; Phase heartbeat lifecycle
(def ^{:stratum 0} start-phase-heartbeat! heartbeat/start!)

(def ^{:stratum 0} stop-phase-heartbeat!
  "Stop a phase heartbeat handle. Nil-safe."
  heartbeat/stop!)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish-workflow-failed! [event-stream context last-phase]
  (let [{:keys [error options]} (failure-event/data context)]
    (publish-event! event-stream
                    (merge (es/workflow-failed event-stream (:execution/id context) error options)
                           (workflow-run-fields context :failed last-phase)))))

(defn- ^{:stratum 1} publish-workflow-success! [event-stream context last-phase]
  (let [metrics (build-completion-metrics context)
        event (es/workflow-completed event-stream (:execution/id context)
                                     (:execution/status context)
                                     (get-in context [:execution/metrics :duration-ms])
                                     (when (seq metrics) metrics))]
    (publish-event! event-stream
                    (merge event (workflow-run-fields context :completed last-phase)))))

(defn- ^{:stratum 1} publish-pr-created-events!
  "Publish canonical `:pr/created` events for workflow-owned PRs."
  [event-stream context]
  (try+
    (doseq [pr (pr-events/completion-prs context)]
      (let [receipt (publish-event! event-stream
                                    (es/pr-created event-stream (:execution/id context) pr))]
        (when (anomaly/any-anomaly? receipt)
          (println (messages/t :warn/publish-pr {:error (:anomaly/message receipt)})))))
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (catch dispatch/nonfatal? _
      (println (messages/t :warn/publish-pr
                          {:error (ex-message (:throwable &throw-context))})))))

;; Lifecycle event publishers
(defn ^{:stratum 1} publish-workflow-started!
  "Publish workflow started event.

   Current events require caller-declared :workflow/intent in execution options.
   Free-form input text is not an authoritative intent map.

   When `context` carries `:routing/trigger-event-id` (set by callers that
   know which routing trigger fired this workflow — N5-delta-4 §4.3), the
   field is threaded onto the emitted envelope so the
   automation-edge-correlator can correlate via the explicit-id path."
  [event-stream context]
  (when (dispatch/dispatchable-event-stream? event-stream)
    (try+
      (publish-event! event-stream
                      (merge (es/workflow-started event-stream
                                                  (:execution/id context)
                                                  (select-keys (:execution/workflow context)
                                                               [:workflow/id :workflow/version])
                                                  (assoc (select-keys (:execution/opts context)
                                                                      [:workflow/intent])
                                                         :routing/trigger-event-id
                                                         (:routing/trigger-event-id context)))
                             (workflow-run-fields context :running initial-phase)))
      #_{:clj-kondo/ignore [:unresolved-symbol]}
      (catch dispatch/nonfatal? _
        (dispatch/publication-failure event-stream (:throwable &throw-context) :warn/publish-started)))))

(defn ^{:stratum 1} publish-phase-started!
  "Publish phase entry with expected-agent facts from the executing interceptor."
  [event-stream context phase-name & [opts]]
  (when (dispatch/dispatchable-event-stream? event-stream)
    (try
      (publish-event! event-stream
                      (merge (es/phase-started event-stream (:execution/id context) phase-name
                                               {:phase/index (:execution/phase-index context)} opts)
                             (workflow-run-fields context :running (name phase-name))))
      (catch Exception e
        (dispatch/publication-failure event-stream e :warn/publish-phase-started)))))

(defn ^{:stratum 1} publish-phase-completed!
  "Publish phase completed event."
  [event-stream context phase-name result]
  (when (dispatch/dispatchable-event-stream? event-stream)
    (try
      (publish-event! event-stream
                      (es/phase-completed event-stream (:execution/id context) phase-name
                                          (phase-event/data result)))
      (catch Exception e
        (dispatch/publication-failure event-stream e :warn/publish-phase-completed)))))

;; Health check at phase boundary
(defn ^{:stratum 1} check-backend-health-at-boundary!
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
          (when (dispatch/dispatchable-event-stream? event-stream)
            (publish-event! event-stream (self-healing/emit-backend-switch-event sh-ctx switch-result)))
          switch-result))
      (catch Exception e
        (println (messages/t :warn/health-check {:error (ex-message e)}))
        nil))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish-workflow-completed!
  "Return the terminal receipt or current-stream refusal.
   Auxiliary PR notifications cannot replace an acknowledged terminal receipt."
  [event-stream context]
  (when (dispatch/dispatchable-event-stream? event-stream)
    (try+
      (let [last-phase (or (some-> (:execution/current-phase context) name) "")
            failed? (phase/failed? context)
            publisher (if failed? publish-workflow-failed! publish-workflow-success!)
            receipt (publisher event-stream context last-phase)]
        (when (and (not failed?) (not (anomaly/any-anomaly? receipt)))
          (publish-pr-created-events! event-stream context))
        receipt)
      #_{:clj-kondo/ignore [:unresolved-symbol]}
      (catch dispatch/nonfatal? _
        (dispatch/publication-failure event-stream (:throwable &throw-context) :warn/publish-completed)))))
