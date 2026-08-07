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
(ns ai.miniforge.effect-transaction.store
  "Create-only transaction storage and exact-value lifecycle transitions."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.messages :as msg]
   [ai.miniforge.effect-transaction.persistence :as persistence]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} record-file persistence/record-file)

(defn- ^{:stratum 0} transition-conflict
  [expected current]
  (anomaly/sub-anomaly :conflict
                       :anomalies.effect-transaction/transition-conflict
                       (msg/t :record/transition-conflict)
                       {:effect/id (:effect/id expected)
                        :effect/expected-state (:effect/state expected)
                        :effect/current-state (:effect/state current)}))

(defn ^{:stratum 0} create!
  "Publish one complete proposal without replacing an existing effect ID."
  [dir record]
  (persistence/create! dir record))

(defn ^{:stratum 0} read-record
  "Read one record by id, nil when absent, or an anomaly on corruption."
  [dir id]
  (persistence/read-record dir id))

(defn ^{:stratum 0} list-records
  "Read every complete transaction record under `dir`."
  [dir]
  (persistence/list-records dir))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} transition-under-lock
  [dir expected replacement]
  (let [current (persistence/read-record dir (:effect/id expected))]
    (cond
      (anomaly/anomaly? current) current
      (not= expected current) (transition-conflict expected current)
      :else (persistence/replace! dir replacement))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} transition!
  "Replace `expected` only when it is still the exact durable record."
  [dir expected replacement]
  (let [id (:effect/id expected)]
    (persistence/with-record-lock
     dir id (partial transition-under-lock dir expected replacement))))
