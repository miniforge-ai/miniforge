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
(ns ai.miniforge.effect-transaction.authorization
  "Authorization derived exclusively from a durable effect proposal."
  (:require
   [ai.miniforge.effect-transaction.messages :as msg]
   [ai.miniforge.effect-transaction.record :as record]
   [ai.miniforge.execution-grant.interface :as grant])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} effect-scope
  [t]
  (assoc (:effect/proposal t) :effect/id (:effect/id t)))

(defn- ^{:stratum 0} grant-conflict
  "Return a conflict when a grant is not the authority named by `t`."
  [t grant-record]
  (cond
    (not= (:effect/grant-id t) (:grant/id grant-record))
    (record/wrong-state (msg/t :grant/id-mismatch)
                        {:effect/id (:effect/id t)
                         :effect/grant-id (:effect/grant-id t)
                         :grant/id (:grant/id grant-record)})

    (not= (:effect/class t) (:grant/effect-class grant-record))
    (record/wrong-state (msg/t :grant/effect-class-mismatch)
                        {:effect/id (:effect/id t)
                         :effect/class (:effect/class t)
                         :grant/effect-class (:grant/effect-class grant-record)})

    :else nil))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} evaluate
  "Check the named grant against durable scope and a one-operation usage."
  [t grant-record ^Instant now]
  (or (grant-conflict t grant-record)
      (grant/authorize grant-record
                       {:effect/scope (effect-scope t)
                        :usage/count 1}
                       now)))
