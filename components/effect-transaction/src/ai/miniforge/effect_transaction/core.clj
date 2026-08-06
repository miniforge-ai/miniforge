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
(ns ai.miniforge.effect-transaction.core
  "Durable record lifecycle and coordinator facade for effects."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.commit :as commit]
   [ai.miniforge.effect-transaction.messages :as msg]
   [ai.miniforge.effect-transaction.reconcile :as reconcile]
   [ai.miniforge.effect-transaction.schema :as schema]
   [ai.miniforge.effect-transaction.store :as store]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} valid?
  "True when `t` satisfies the closed EffectTransaction schema."
  [t]
  (m/validate schema/EffectTransaction t))

(defn- ^{:stratum 0} invalid
  [message t]
  (anomaly/sub-anomaly :invalid-input
                       :anomalies.effect-transaction/invalid
                       message
                       {:explain (me/humanize (m/explain schema/EffectTransaction t))}))

(def ^{:stratum 0} commit!
  "Authorize and execute a durable proposal, recording an honest outcome."
  commit/commit!)

(def ^{:stratum 0} reconcile!
  "Ask the external system to settle an unknown effect outcome."
  reconcile/reconcile!)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} propose!
  "Record an irreversible effect durably before anything happens."
  ([{:keys [dir] :as opts}]
   (if (nil? dir)
     (anomaly/sub-anomaly :invalid-input
                          :anomalies.effect-transaction/no-store-dir
                          (msg/t :proposal/no-store)
                          {})
     (propose! dir opts (Instant/now))))
  ([dir {:keys [effect-class grant-id envelope-id] :as opts} ^Instant now]
   (let [t {:effect/id (random-uuid)
            :effect/class effect-class
            :effect/grant-id grant-id
            :effect/envelope-id envelope-id
            :effect/proposal (get opts :proposal {})
            :effect/state :proposed
            :effect/at now
            :effect/updated-at now}]
     (if (valid? t)
       (do (store/write! dir t) t)
       (invalid (msg/t :record/input-invalid) t)))))
