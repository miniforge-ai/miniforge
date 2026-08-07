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
(ns ai.miniforge.effect-transaction.record
  "Validation and durable lifecycle changes for effect transactions."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.messages :as msg]
   [ai.miniforge.effect-transaction.schema :as schema]
   [ai.miniforge.effect-transaction.store :as store]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   [java.time Instant]
   [java.util Date]))

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

(defn ^{:stratum 0} wrong-state
  "Return a lifecycle-position conflict for an internal coordinator."
  [message data]
  (anomaly/sub-anomaly :conflict
                       :anomalies.effect-transaction/wrong-state
                       message
                       data))

(defn ^{:stratum 0} unresolved
  "Return a transient unavailable result without changing the record."
  [message data]
  (anomaly/sub-anomaly :unavailable
                       :anomalies.effect-transaction/unresolved
                       message
                       data))

(defn- ^{:stratum 0} normalize-instant
  [value]
  (if (instance? Date value)
    (.toInstant ^Date value)
    value))

(def ^{:stratum 0} not-found store/not-found)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} advance!
  "Compare-and-set `changes` against the exact durable value of `t`."
  [dir t changes ^Instant now]
  (if (not= (:effect/id t) (get changes :effect/id (:effect/id t)))
    (wrong-state (msg/t :record/id-immutable)
                 {:effect/id (:effect/id t)
                  :effect/requested-id (:effect/id changes)})
    (let [t' (assoc (merge t changes)
                    :effect/updated-at (normalize-instant now))]
      (if (valid? t')
        (store/transition! dir t t')
        (invalid (msg/t :record/change-invalid) t')))))

(defn ^{:stratum 1} propose!
  "Record an irreversible effect durably before anything happens."
  ([{:keys [dir] :as opts}]
   (if (nil? dir)
     (anomaly/sub-anomaly :invalid-input
                          :anomalies.effect-transaction/no-store-dir
                          (msg/t :proposal/no-store)
                          {})
     (propose! dir opts (Instant/now))))
  ([dir {:keys [effect-id effect-class grant-id envelope-id proposal]} ^Instant now]
   (let [at (normalize-instant now)
         t {:effect/id (if (some? effect-id) effect-id (random-uuid))
            :effect/class effect-class
            :effect/grant-id grant-id
            :effect/envelope-id envelope-id
            :effect/proposal (if proposal proposal {})
            :effect/state :proposed
            :effect/at at
            :effect/updated-at at}]
     (if (valid? t)
       (store/create! dir t)
       (invalid (msg/t :record/input-invalid) t)))))
