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
(ns ai.miniforge.agent-environment.lease
  "The ledger's rules: one environment per tenant/run/task key, a lease
   generation that only grows, and which calls each ledger state admits.
   Pure functions over ledger entries; the controller applies them."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} namespace-prefix
  "Marks namespaces this contract derived, so an operator's own namespace
   is never mistaken for one."
  "mf-env-")

(def ^{:stratum 0} namespace-hash-chars
  "Hex digits of the key hash kept in a namespace name: 96 bits, well
   inside the 63-character label limit."
  24)

(def ^{:stratum 0} admitted-states
  "Ledger states in which each leased operation may run."
  {:environment/readiness #{:state/provisioned :state/cancelled}
   :environment/exec #{:state/provisioned}
   :environment/copy #{:state/provisioned}
   :environment/persist-artifacts #{:state/provisioned :state/cancelled}
   :environment/cancel #{:state/provisioned :state/cancelled}
   :environment/release #{:state/provisioned :state/cancelled :state/cleanup-uncertain}})

(def ^{:stratum 0} cleanup-operations
  "Calls still admitted after an environment expires: audit and cleanup."
  #{:environment/persist-artifacts :environment/cancel :environment/release})

(def ^{:stratum 0} state->refusal
  "Why a call is refused when the ledger entry is in this state."
  {:state/provisioning :anomalies.agent-environment/acquire-in-flight
   :state/cancelled :anomalies.agent-environment/environment-cancelled
   :state/released :anomalies.agent-environment/environment-released
   :state/cleanup-uncertain :anomalies.agent-environment/cleanup-uncertain})

(defn ^{:stratum 0} request-key
  [request]
  [(:environment/tenant-id request) (:environment/run-id request) (:environment/task-id request)])

(defn ^{:stratum 0} lease-key
  [lease]
  [(:lease/tenant-id lease) (:lease/run-id lease) (:lease/task-id lease)])

(defn ^{:stratum 0} current-lease
  [entry]
  (get-in entry [:entry/environment :environment/lease]))

(defn ^{:stratum 0} ->instant
  "Any `inst?` value as an Instant. Instants and Timestamps keep their
   sub-millisecond precision; other dates (some, like java.sql.Date, refuse
   `toInstant`) convert through their epoch millisecond."
  ^Instant [t]
  (cond
    (instance? Instant t) t
    (instance? java.sql.Timestamp t) (.toInstant ^java.sql.Timestamp t)
    :else (Instant/ofEpochMilli (inst-ms t))))

(defn ^{:stratum 0} decision
  ([kind] {:decision/kind kind})
  ([kind reason] {:decision/kind kind :decision/reason reason}))

(defn ^{:stratum 0} lease-for
  [request uid generation]
  {:lease/tenant-id (:environment/tenant-id request)
   :lease/run-id (:environment/run-id request)
   :lease/task-id (:environment/task-id request)
   :lease/environment-uid uid
   :lease/generation generation})

(defn ^{:stratum 0} reserve
  "The entry a `:new` decision writes: provisioning, one generation past
   whatever the key held before."
  [entry request]
  {:entry/state :state/provisioning
   :entry/generation (inc (get entry :entry/generation 0))
   :entry/request request
   :entry/environment nil})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} expired?
  "True once `now` has reached the request's expiry."
  [request now]
  (not (.isAfter (->instant (:environment/expires-at request)) (->instant now))))

(defn ^{:stratum 1} namespace-name
  "The namespace for `request`'s key: the same key always names the same
   namespace, across generations and controller restarts."
  [request]
  (let [digest (content-hash/content-hash (mapv str (request-key request)))]
    (str namespace-prefix (subs digest 0 namespace-hash-chars))))

(defn ^{:stratum 1} acquire-decision
  "`:new` when the key has no live entry, `:duplicate` when the identical
   request already holds a provisioned environment, else `:refuse`."
  [entry request]
  (let [state (:entry/state entry)]
    (cond
      (or (nil? entry) (= :state/released state)) (decision :new)
      (not= request (:entry/request entry)) (decision :refuse :anomalies.agent-environment/conflicting-request)
      (= :state/provisioned state) (decision :duplicate)
      :else (decision :refuse (state->refusal state)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} lease-refusal
  "Nil when `lease` is the entry's current lease, `operation` runs in the
   entry's state, and the environment has not expired at `now` (cleanup and
   audit calls run after expiry); otherwise the refusal reason."
  [entry operation lease now]
  (cond
    (or (nil? entry) (nil? lease))
    :anomalies.agent-environment/unknown-lease

    (not= lease (current-lease entry))
    :anomalies.agent-environment/stale-lease

    (not (contains? (admitted-states operation) (:entry/state entry)))
    (get state->refusal (:entry/state entry) :anomalies.agent-environment/operation-not-admitted)

    (and (expired? (:entry/request entry) now) (not (cleanup-operations operation)))
    :anomalies.agent-environment/environment-expired))
