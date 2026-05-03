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
   [clojure.string :as str]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.interface :as phase]
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

(defn- websocket-stream?
  [event-stream]
  (boolean (:websocket event-stream)))

(defn- durable-event-stream?
  [event-stream]
  (instance? clojure.lang.IDeref event-stream))

(defn- dispatchable-event-stream?
  [event-stream]
  (or (websocket-stream? event-stream)
      (durable-event-stream? event-stream)))

(defn publish-event!
  "Publish event to event stream or dashboard WebSocket.
   Safe to call with nil event-stream (no-op)."
  [event-stream event]
  (when (dispatchable-event-stream? event-stream)
    (try
      (cond
        (websocket-stream? event-stream)
        (publish-via-websocket! event-stream event)

        (durable-event-stream? event-stream)
        (es/publish! event-stream event)

        :else
        nil)
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
      (:workflow/pr-info context) (assoc :pr-info (:workflow/pr-info context))
      (seq (:execution/dag-pr-infos context)) (assoc :pr-infos (:execution/dag-pr-infos context))
      (:workflow/evidence-bundle-id context)
      (assoc :workflow/evidence-bundle-id (:workflow/evidence-bundle-id context)))))

(def ^:private inner-failure-statuses
  #{:error :failed :failure})

(defn- inner-result-failed?
  "True when the phase's inner agent result carries a failure status.

   The phase wrapper can set :status :completed on the outer phase map
   (meaning the interceptor ran without crashing) while the inner
   :result still carries :status :error / :failed / :failure from an
   agent that failed. An inner failure MUST propagate to the phase
   outcome; otherwise events report :success over an :error result
   and downstream observers (like the DAG orchestrator) see ambiguity."
  [result]
  (boolean
    (or (contains? inner-failure-statuses (get-in result [:result :status]))
        (false? (get-in result [:result :success?])))))

(defn- build-phase-event-data
  "Build event data map for a phase completion event.

   `error-info` is sourced from any of five shapes the phase machinery uses:

   1. `(:error result)`          — top-level error map (early failures)
   2. `[:result :error]`         — inner agent result error (post-inner-fail)
   3. `[:phase :error]`          — error attached by an `error-*` interceptor
                                   that fell through to `phase/fail-phase`
                                   (the release-phase no-files path before
                                   this fix landed produced bare failure
                                   events with no payload because of this
                                   missing branch)
   4. `:phase/gate-errors`       — gate validation failures
   5. `(:message result)`        — last-resort string message"
  [result]
  (let [succeeded? (and (get result :success? (phase/succeeded-or-done? result))
                        (not (inner-result-failed? result)))
        outcome    (if succeeded? :success :failure)
        duration-ms (get result :duration-ms
                      (get-in result [:phase/metrics :duration-ms]
                        (get-in result [:metrics :duration-ms])))
        error-info (when-not succeeded?
                     (or (:error result)
                         (get-in result [:result :error])
                         (get-in result [:phase :error])
                         (when-let [msg (get-in result [:phase/gate-errors])]
                           {:message (messages/t :status/gate-failed) :details msg})
                         (when-let [msg (:message result)]
                           {:message msg})))
        transition-request (phase/transition-request result)
        tokens   (get-in result [:metrics :tokens]
                   (get-in result [:phase/metrics :tokens]))
        cost-usd (get-in result [:metrics :cost-usd]
                   (get-in result [:phase/metrics :cost-usd]))]
    (cond-> {:outcome outcome :duration-ms duration-ms}
      error-info    (assoc :error error-info)
      transition-request (assoc :phase/transition-request transition-request)
      tokens        (assoc :tokens tokens)
      cost-usd      (assoc :cost-usd cost-usd))))

;------------------------------------------------------------------------------ Layer 1.5
;; Supervisory entity builders

(def ^:private initial-phase "init")

(defn- workflow-run-fields
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
     :workflow-run/correlation-id (:execution/id context)}))

(defn- repo-from-pr-url
  "Extract `owner/repo` from a GitHub-style PR URL.

   Returns nil when the URL does not look like a GitHub pull URL."
  [pr-url]
  (when (string? pr-url)
    (let [parts (str/split pr-url #"/")]
      (when-let [pull-index (some #(when (= "pull" (nth parts % nil)) %) (range (count parts)))]
        (when (>= pull-index 2)
          (str (nth parts (- pull-index 2)) "/" (nth parts (dec pull-index))))))))

(defn- normalize-pr-info
  "Normalize release / DAG PR info into the canonical `:pr/*` event payload."
  [pr-info]
  (let [number (or (:pr-number pr-info) (:pr/id pr-info))
        url    (or (:pr-url pr-info) (:pr/url pr-info))
        repo   (or (:pr/repo pr-info) (repo-from-pr-url url))]
    (when (and repo number url)
      {:pr/repo repo
       :pr/number number
       :pr/url url
       :pr/branch (or (:branch pr-info) (:pr/branch pr-info))
       :pr/title (or (:title pr-info) (:pr/title pr-info))
       :pr/author (or (:author pr-info) (:pr/author pr-info))
       :pr/merge-order (:merge-order pr-info)})))

(defn- completion-prs
  "Collect normalized PR payloads from workflow context.

   The release phase produces a single `:workflow/pr-info`; DAG runs may also
   accumulate `:execution/dag-pr-infos`. We preserve order and de-duplicate
   by repo+number so `workflow/completed` and emitted `pr/created` events are
   stable even when both sources describe the same PR."
  [context]
  (let [raw-prs (concat (get context :execution/dag-pr-infos [])
                        (cond-> [] (:workflow/pr-info context) (conj (:workflow/pr-info context))))]
    (->> raw-prs
         (keep normalize-pr-info)
         (reduce (fn [acc pr]
                   (let [pr-key [(:pr/repo pr) (:pr/number pr)]]
                     (if (some #(= pr-key [(:pr/repo %) (:pr/number %)]) acc)
                       acc
                       (conj acc pr))))
                 []))))

(defn- publish-pr-created-events!
  "Publish canonical `:pr/created` events for workflow-owned PRs."
  [event-stream context]
  (doseq [pr (completion-prs context)]
    (publish-event! event-stream
                    (es/pr-created event-stream (:execution/id context) pr))))

;------------------------------------------------------------------------------ Layer 2
;; Lifecycle event publishers

(defn publish-workflow-started!
  "Publish workflow started event."
  [event-stream context]
  (when (dispatchable-event-stream? event-stream)
    (try
      (publish-event! event-stream
                      (merge (es/workflow-started event-stream
                                                  (:execution/id context)
                                                  (select-keys (:execution/workflow context)
                                                               [:workflow/id :workflow/version]))
                             (workflow-run-fields context :running initial-phase)))
      (catch Exception e
        (println (messages/t :warn/publish-started {:error (ex-message e)}))))))

(defn publish-workflow-completed!
  "Publish workflow completed/failed event."
  [event-stream context]
  (when (dispatchable-event-stream? event-stream)
    (try
      (let [wf-id        (:execution/id context)
            metrics-opts (build-completion-metrics context)
            last-phase   (or (some-> (:execution/current-phase context) name) "")]
        (if (phase/failed? context)
          (publish-event! event-stream
                          (merge (es/workflow-failed event-stream wf-id
                                                    {:message (messages/t :status/failed)
                                                     :errors (:execution/errors context)})
                                 (workflow-run-fields context :failed last-phase)))
          (do
            (publish-event! event-stream
                            (merge (es/workflow-completed event-stream wf-id
                                                         (:execution/status context)
                                                         (get-in context [:execution/metrics :duration-ms])
                                                         (when (seq metrics-opts) metrics-opts))
                                   (workflow-run-fields context :completed last-phase)))
            (publish-pr-created-events! event-stream context))))
      (catch Exception e
        (println (messages/t :warn/publish-completed {:error (ex-message e)}))))))

(defn publish-phase-started!
  "Publish phase started event."
  [event-stream context phase-name]
  (when (dispatchable-event-stream? event-stream)
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
  (when (dispatchable-event-stream? event-stream)
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
          (when (dispatchable-event-stream? event-stream)
            (publish-event! event-stream (self-healing/emit-backend-switch-event sh-ctx switch-result)))
          switch-result))
      (catch Exception e
        (println (messages/t :warn/health-check {:error (ex-message e)}))
        nil))))
