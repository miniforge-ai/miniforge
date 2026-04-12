;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.workflow.observe-phase
  "N2 Observe phase: autonomous PR monitoring after release.

   Wires the PR monitor loop into the workflow execution pipeline.
   After the Release phase creates a PR, the Observe phase starts
   the monitor loop and runs until the PR is merged, abandoned,
   or budget exhausted.

   This is the N2 §7 implementation. The Observe phase is the final
   phase of the standard SDLC workflow: plan → implement → verify →
   review → release → observe."
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.pr-lifecycle.monitor-config :as monitor-config]
            [ai.miniforge.pr-lifecycle.monitor-loop :as monitor-loop]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.schema.interface :as schema]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Config loading + defaults

(def ObservePhaseDefaults
  [:map
   [:agent {:optional true} [:maybe keyword?]]
   [:gates [:vector keyword?]]
   [:budget [:map
             [:tokens nat-int?]
             [:iterations pos-int?]
             [:time-seconds pos-int?]]]])

(def ObservePhaseConfig
  [:map
   [:observe/phase-defaults ObservePhaseDefaults]])

(def ObserveMonitorConfig
  [:map
   [:worktree-path :string]
   [:self-author [:maybe :string]]
   [:generate-fn {:optional true} any?]
   [:event-bus {:optional true} any?]
   [:logger {:optional true} any?]
   [:poll-interval-ms nat-int?]
   [:max-fix-attempts-per-comment pos-int?]
   [:max-total-fix-attempts-per-pr pos-int?]
   [:abandon-after-hours pos-int?]])

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

(defn- load-observe-phase-config
  []
  (if-let [res (io/resource "config/workflow/observe-phase.edn")]
    (->> res slurp edn/read-string (validate! ObservePhaseConfig))
    (response/throw-anomaly! :anomalies/not-found
                            "Missing classpath resource: config/workflow/observe-phase.edn"
                            {:hint "Add components/workflow/resources to your classpath"}))))

(defn- load-monitor-defaults
  []
  (monitor-config/monitor-defaults))

(defn- present-overrides
  [overrides]
  (into {} (remove (comp nil? val)) overrides))

(def default-config
  "Default Observe phase configuration loaded from EDN."
  (:observe/phase-defaults (load-observe-phase-config)))

;; Register defaults on load
(registry/register-phase-defaults! :observe default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- resolve-monitor-config
  [ctx logger generate-fn event-bus]
  (let [worktree-path (or (get-in ctx [:execution/worktree-path])
                          (get-in ctx [:worktree-path]))
        shared-defaults (load-monitor-defaults)]
    (validate!
     ObserveMonitorConfig
     (merge shared-defaults
            {:worktree-path worktree-path
             :self-author (or (get-in ctx [:execution/self-author])
                              (get-in ctx [:config :github/self-author])
                              (:self-author shared-defaults))
             :generate-fn generate-fn
             :event-bus event-bus
             :logger logger}
            (present-overrides
             {:poll-interval-ms (get-in ctx [:config :pr-monitor/poll-interval-ms])
              :max-fix-attempts-per-comment
              (get-in ctx [:config :pr-monitor/max-fix-attempts-per-comment])
              :max-total-fix-attempts-per-pr
              (get-in ctx [:config :pr-monitor/max-total-fix-attempts-per-pr])
              :abandon-after-hours
              (get-in ctx [:config :pr-monitor/abandon-after-hours])})))))

(defn enter-observe
  "Execute the Observe phase.

   Reads PR info from context (set by Release phase), creates and runs
   the PR monitor loop until a terminal condition is reached:
   - All PRs merged
   - Budget exhausted (human escalation emitted)
   - PRs closed externally
   - No open PRs remain

   pr-lifecycle is a direct dependency of the workflow component."
  [ctx]
  (let [pr-infos (or (get-in ctx [:execution/dag-pr-infos])
                     ;; Single PR from release phase
                     (when-let [pr-info (get-in ctx [:execution/phase-results :release :pr-info])]
                       [pr-info]))
        start-time (System/currentTimeMillis)]

    (if (empty? pr-infos)
      ;; No PRs to observe — skip phase
      (-> ctx
          (assoc-in [:phase :name] :observe)
          (assoc-in [:phase :status] :completed)
          (assoc-in [:phase :result]
                    (response/success {:observe/status :skipped
                                       :observe/reason "No PRs to observe"})))

      (let [logger (get-in ctx [:execution/logger])
            generate-fn (get-in ctx [:execution/generate-fn])
            event-bus (get-in ctx [:execution/event-bus])
            monitor-config (resolve-monitor-config ctx logger generate-fn event-bus)
            self-author (:self-author monitor-config)

            monitor (monitor-loop/create-monitor monitor-config)
            evidence (monitor-loop/run-monitor-loop monitor self-author)

            result-data {:observe/status :completed
                         :observe/evidence evidence
                         :observe/prs-monitored (count pr-infos)
                         :observe/duration-hours (:duration-hours evidence)
                         :observe/comments-received (:comments-received evidence)
                         :observe/comments-addressed (:comments-addressed evidence)
                         :observe/fixes-pushed (count (:fixes-pushed evidence))
                         :observe/questions-answered (count (:questions-answered evidence))}]

        (-> ctx
            (assoc-in [:phase :name] :observe)
            (assoc-in [:phase :started-at] start-time)
            (assoc-in [:phase :status] :completed)
            (assoc-in [:phase :result] (response/success result-data))
            (assoc :execution/pr-lifecycle-evidence evidence))))))

(defn leave-observe
  "Post-processing for Observe phase. Records evidence and duration metrics."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at] (System/currentTimeMillis))
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:metrics :observe :duration-ms] duration-ms)
        (update-in [:execution :phases-completed] (fnil conj []) :observe)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-observe
  "Handle Observe phase errors."
  [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] {:message (ex-message ex)
                                  :data (ex-data ex)})))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod registry/get-phase-interceptor :observe
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::observe
     :config merged
     :enter (fn [ctx]
              (enter-observe (assoc ctx :phase-config merged)))
     :leave leave-observe
     :error error-observe}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get interceptor
  (registry/get-phase-interceptor {:phase :observe})

  ;; Check defaults
  (registry/phase-defaults :observe)

  ;; Typical execution context keys the Observe phase reads:
  ;; :execution/dag-pr-infos — vector of PR info maps from Release phase
  ;; :execution/logger — structured logger
  ;; :execution/worktree-path — git repo path
  ;; :execution/generate-fn — LLM generation function
  ;; :execution/event-bus — PR lifecycle event bus
  ;; :execution/self-author — miniforge's GitHub login
  ;; :config :pr-monitor/* — monitor config overrides

  :leave-this-here)
