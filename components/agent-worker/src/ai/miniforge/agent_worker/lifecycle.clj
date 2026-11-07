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
(ns ai.miniforge.agent-worker.lifecycle
  "One attempt through the worker contract: admit, preflight the explicitly
   selected worker (never another), record the submission, start; then
   session-checked inspect, input, cancel, artifacts and usage. Each
   session call first confirms the environment lease is still current."
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-worker.admission :as admission]
   [ai.miniforge.agent-worker.controller :as controller]
   [ai.miniforge.agent-worker.interface.protocol :as protocol]
   [ai.miniforge.agent-worker.refusal :as refusal]
   [ai.miniforge.agent-worker.schema :as schema]
   [ai.miniforge.agent-worker.session :as session]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} worker-for [wc config] (get (:worker/workers wc) (:config/worker config)))

(defn- ^{:stratum 0} session-for
  [binding worker-kind native turn]
  {:session/binding binding
   :session/worker worker-kind
   :session/native-id (:native/id native)
   :session/process-ref (:native/process-ref native)
   :session/turn turn})

(defn- ^{:stratum 0} session-refusal
  [acting session record]
  (cond
    (not= (:acting/tenant-id acting) (get-in session [:session/binding :worker/tenant-id]))
    :anomalies.agent-worker/forged-tenant

    (not= (:acting/principal-id acting) (get-in session [:session/binding :worker/principal-id]))
    :anomalies.agent-worker/forged-principal

    (not= session (:record/result record))
    :anomalies.agent-worker/unknown-session))

(defn- ^{:stratum 0} turn-refusal
  "Why `session` cannot start another turn, or nil: the worker steers, the
   turn is shown finished, and the bound spend grant still authorizes it."
  [wc acting grant session worker handle]
  (let [binding (:session/binding session)
        config (get-in (controller/record wc binding) [:record/submission :submission/config])
        steers? (= :steering/between-turns (:capabilities/steering (protocol/capabilities worker)))
        status (when steers? (protocol/inspect worker handle session))
        state (:status/state status)]
    (cond
      (not steers?) (refusal/reason-anomaly :anomalies.agent-worker/steering-unsupported {})
      (anomaly/anomaly? status) status
      (= :state/running state) (refusal/reason-anomaly :anomalies.agent-worker/turn-in-progress {})
      (not (contains? schema/finished-states state)) (refusal/reason-anomaly :anomalies.agent-worker/turn-unsettled {})
      :else (some-> (admission/grant-refusal grant acting binding config ((:worker/clock wc)))
                    (refusal/reason-anomaly {})))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} launch-refusal
  "Why a new submission cannot launch, in order: no such worker, environment
   not ready, preflight failed, then (current at launch) spend grant or budget."
  [wc acting grant handle submission]
  (let [binding (:submission/binding submission)
        config (:submission/config submission)
        worker (worker-for wc config)
        ready (when worker (env/readiness (:env/controller handle) acting (:env/lease handle)))
        failures (when (:readiness/ready? ready) (protocol/preflight worker handle))
        spend (when (and (:readiness/ready? ready) (empty? failures))
                (or (admission/grant-refusal grant acting binding config ((:worker/clock wc)))
                    (admission/budget-refusal grant config (protocol/capabilities worker))))]
    (cond
      (nil? worker) (refusal/reason-anomaly :anomalies.agent-worker/worker-unavailable {})
      (anomaly/anomaly? ready) ready
      (not (:readiness/ready? ready)) (refusal/reason-anomaly :anomalies.agent-worker/environment-not-ready {})
      (seq failures) (refusal/reason-anomaly :anomalies.agent-worker/worker-preflight-failed {:failures failures})
      spend (refusal/reason-anomaly spend {}))))

(defn- ^{:stratum 1} launch
  [wc worker handle submission]
  (let [binding (:submission/binding submission)
        native (protocol/start! worker handle submission)
        result (if (anomaly/anomaly? native)
                 native
                 (session-for binding (get-in submission [:submission/config :config/worker]) native 1))]
    (controller/settle! wc binding #(assoc % :record/result result))
    result))

(defn- ^{:stratum 1} with-session
  "Run `(f worker handle)` for a recorded session. `check` is `:check/ready`
   (a new turn: the environment must be ready), `:check/lease` (the lease
   must be current) or `:check/none` (artifacts: the environment allows them
   after expiry and checks the lease itself)."
  [wc acting session check f]
  (let [binding (:session/binding session)
        refused (session-refusal acting session (controller/record wc binding))
        handle (controller/env-handle wc acting binding)
        ready (when-not (or refused (= :check/none check)) (env/readiness (:env/controller handle) acting (:env/lease handle)))]
    (cond
      refused (refusal/reason-anomaly refused {})
      (anomaly/anomaly? ready) ready
      (and (= :check/ready check) (not (:readiness/ready? ready)))
      (refusal/reason-anomaly :anomalies.agent-worker/environment-not-ready {})
      :else (f (get (:worker/workers wc) (:session/worker session)) handle))))

(defn- ^{:stratum 1} next-turn
  "Start the next turn only for the call that reserved it; a competing call
   is refused, and a failed turn leaves the recorded session as it was."
  [wc acting grant session worker handle text]
  (let [binding (:session/binding session)
        refused (turn-refusal wc acting grant session worker handle)
        reserved? (and (nil? refused) (controller/reserve-turn! wc session))
        submission (:record/submission (controller/record wc binding))
        native (when reserved? (protocol/send-input! worker handle submission session text))
        steered (when (and native (not (anomaly/anomaly? native)))
                  (assoc (session-for binding (:session/worker session) native (inc (:session/turn session)))
                         :session/native-id (or (:native/id native) (:session/native-id session))))]
    (when reserved? (controller/settle! wc binding #(controller/turn-settled % steered)))
    (cond
      refused refused
      (not reserved?) (refusal/reason-anomaly :anomalies.agent-worker/turn-in-progress {})
      :else (or steered native))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start!
  "A recorded attempt reconciles on identity, binding and config alone; a
   new one launches only once `launch-refusal` passes."
  [wc acting grant lease submission]
  (let [binding (:submission/binding submission)
        refused (or (admission/binding-refusal acting lease binding)
                    (admission/config-refusal binding (:submission/config submission)))
        seen (when-not refused (session/submission-decision (controller/record wc binding) submission))
        handle (controller/env-handle wc acting binding)
        blocked (when (= :new (:decision/kind seen)) (launch-refusal wc acting grant handle submission))
        decision (if (and (= :new (:decision/kind seen)) (nil? blocked)) (controller/claim! wc submission) seen)]
    (cond
      refused (refusal/reason-anomaly refused {})
      blocked blocked
      (= :new (:decision/kind decision)) (launch wc (worker-for wc (:submission/config submission)) handle submission)
      (= :duplicate (:decision/kind decision)) (or (:record/result (controller/record wc binding))
                                                   (refusal/reason-anomaly :anomalies.agent-worker/start-in-flight {}))
      :else (refusal/reason-anomaly (:decision/reason decision) {}))))

(defn ^{:stratum 2} inspect
  [wc acting session]
  (with-session wc acting session :check/lease #(protocol/inspect %1 %2 session)))

(defn ^{:stratum 2} send-input!
  [wc acting grant session text]
  (with-session wc acting session :check/ready #(next-turn wc acting grant session %1 %2 text)))

(defn ^{:stratum 2} request-cancel!
  [wc acting session]
  (with-session wc acting session :check/lease #(protocol/request-cancel! %1 %2 session)))

(defn ^{:stratum 2} persist-artifacts!
  [wc acting session]
  (with-session wc acting session :check/none
    #(env/persist-artifacts! (:env/controller %2) acting (:env/lease %2) (protocol/artifact-paths %1 %2 session))))

(defn ^{:stratum 2} usage
  [wc acting session]
  (with-session wc acting session :check/lease
    #(let [reported (protocol/usage %1 %2 session)] (cond-> reported (not (anomaly/anomaly? reported)) session/normalize-usage))))
