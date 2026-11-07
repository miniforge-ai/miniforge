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
   [ai.miniforge.agent-worker.session :as session]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} worker-for [wc config] (get (:worker/workers wc) (:config/worker config)))

(defn- ^{:stratum 0} digest-refusal
  [binding config]
  (when (not= (session/config-digest config) (:worker/config-digest binding))
    :anomalies.agent-worker/config-digest-mismatch))

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

    (not= session (:record/result record))
    :anomalies.agent-worker/unknown-session))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admission-refusal
  [wc acting grant lease submission]
  (let [binding (:submission/binding submission)
        config (:submission/config submission)
        worker (worker-for wc config)]
    (or (admission/binding-refusal acting lease binding)
        (digest-refusal binding config)
        (when (nil? worker) :anomalies.agent-worker/worker-unavailable)
        (admission/grant-refusal grant acting binding config ((:worker/clock wc)))
        (admission/budget-refusal grant config (protocol/capabilities worker)))))

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
  "Run `(f worker handle)` for a recorded session whose lease is current."
  [wc acting session f]
  (let [binding (:session/binding session)
        refused (session-refusal acting session (controller/record wc binding))
        handle (controller/env-handle wc acting binding)
        ready (when-not refused (env/readiness (:env/controller handle) acting (:env/lease handle)))]
    (cond
      refused (refusal/reason-anomaly refused {})
      (anomaly/anomaly? ready) ready
      :else (f (get (:worker/workers wc) (:session/worker session)) handle))))

(defn- ^{:stratum 1} next-turn
  [wc session worker handle text]
  (let [steering (:capabilities/steering (protocol/capabilities worker))
        state (:status/state (protocol/inspect worker handle session))
        submission (:record/submission (controller/record wc (:session/binding session)))
        native (when (and (= :steering/between-turns steering) (not= :state/running state))
                 (protocol/send-input! worker handle submission session text))
        steered (when (and native (not (anomaly/anomaly? native)))
                  (assoc (session-for (:session/binding session) (:session/worker session) native
                                      (inc (:session/turn session)))
                         :session/native-id (or (:native/id native) (:session/native-id session))))]
    (cond
      (= :steering/none steering) (refusal/reason-anomaly :anomalies.agent-worker/steering-unsupported {})
      (= :state/running state) (refusal/reason-anomaly :anomalies.agent-worker/turn-in-progress {})
      (anomaly/anomaly? native) native
      :else (do (controller/settle! wc (:session/binding session) #(assoc % :record/result steered))
                steered))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start!
  [wc acting grant lease submission]
  (let [refused (admission-refusal wc acting grant lease submission)
        worker (worker-for wc (:submission/config submission))
        handle (controller/env-handle wc acting (:submission/binding submission))
        failures (when-not refused (protocol/preflight worker handle))
        decision (when-not (or refused (seq failures)) (controller/claim! wc submission))]
    (cond
      refused (refusal/reason-anomaly refused {})
      (seq failures) (refusal/reason-anomaly :anomalies.agent-worker/worker-preflight-failed {:failures failures})
      (= :new (:decision/kind decision)) (launch wc worker handle submission)
      (= :duplicate (:decision/kind decision)) (:record/result (controller/record wc (:submission/binding submission)))
      :else (refusal/reason-anomaly (:decision/reason decision) {}))))

(defn ^{:stratum 2} inspect
  [wc acting session]
  (with-session wc acting session #(protocol/inspect %1 %2 session)))

(defn ^{:stratum 2} send-input!
  [wc acting session text]
  (with-session wc acting session #(next-turn wc session %1 %2 text)))

(defn ^{:stratum 2} request-cancel!
  [wc acting session]
  (with-session wc acting session #(protocol/request-cancel! %1 %2 session)))

(defn ^{:stratum 2} persist-artifacts!
  [wc acting session]
  (with-session wc acting session
    #(env/persist-artifacts! (:env/controller %2) acting (:env/lease %2) (protocol/artifact-paths %1 %2 session))))

(defn ^{:stratum 2} usage
  [wc acting session]
  (with-session wc acting session #(session/normalize-usage (protocol/usage %1 %2 session))))
