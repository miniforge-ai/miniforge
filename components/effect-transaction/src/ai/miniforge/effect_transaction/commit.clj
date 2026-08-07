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
(ns ai.miniforge.effect-transaction.commit
  "Commit authorization and effect execution for durable proposals."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.authorization :as authorization]
   [ai.miniforge.effect-transaction.call :as call]
   [ai.miniforge.effect-transaction.messages :as msg]
   [ai.miniforge.effect-transaction.record :as record]
   [ai.miniforge.effect-transaction.store :as store]
   [ai.miniforge.execution-grant.interface :as grant])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execute!
  "Persist the authority result, then perform and record the effect."
  [dir t authority ^Instant now effect-fn]
  (let [committing (record/advance! dir t
                                    {:effect/state :committing
                                     :effect/authority authority}
                                    now)]
    (if (anomaly/anomaly? committing)
      committing
      (record/advance! dir committing
                       (call/changes (call/result effect-fn))
                       now))))

(defn- ^{:stratum 0} deny!
  "Record a failed commit-time grant recheck without firing the effect."
  [dir t auth ^Instant now]
  (let [failure (msg/t :grant/recheck-failed
                       {:outcome (name (:grant/outcome auth))})]
    (record/advance! dir t {:effect/state :failed :effect/failure failure} now)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} authorized-commit!
  "Authorize the durable proposal, then execute or record refusal."
  [dir t grant-record ^Instant now effect-fn]
  (let [auth (authorization/evaluate t grant-record now)]
    (cond
      (anomaly/anomaly? auth) auth
      (grant/authorized? auth) (execute! dir t :granted now effect-fn)
      :else (deny! dir t auth now))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} commit!
  "Commit a proposed effect under its recorded grant.

   The durable proposal, not caller usage, supplies authorization scope and
   the one-operation usage count.
   Exceptions from the effect become `:unknown-outcome`; JVM Errors
   propagate after the `:committing` record is durable."
  [dir candidate grant-record _usage ^Instant now effect-fn]
  (let [id (:effect/id candidate)
        t (store/read-record dir id)]
    (cond
      (anomaly/anomaly? t) t

      (nil? t)
      (store/not-found id)

      (not= :proposed (:effect/state t))
      (record/wrong-state (msg/t :commit/not-proposed)
                          (record/lifecycle-position t))

      (= :authority/unenforced grant-record)
      (execute! dir t :unenforced now effect-fn)

      :else
      (authorized-commit! dir t grant-record now effect-fn))))
