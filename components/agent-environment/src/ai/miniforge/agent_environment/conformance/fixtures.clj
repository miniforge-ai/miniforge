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
(ns ai.miniforge.agent-environment.conformance.fixtures
  "Synthetic tenants, grants and requests the conformance checks run on.
   A world is one fresh controller with its own receipt log."
  (:require
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.lifecycle :as lifecycle]
   [ai.miniforge.agent-environment.schema :as schema]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.tenancy.interface :as tenancy])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} grant-ttl-seconds
  "Long enough that no check outlives its grant."
  3600)

(def ^{:stratum 0} template-hashes
  "Two distinct template hashes: the second makes a conflicting request."
  [(apply str (repeat 64 "a")) (apply str (repeat 64 "b"))])

(def ^{:stratum 0} policy-hash
  "A fixed policy hash: the checks vary templates, never policies."
  (apply str (repeat 64 "c")))

(def ^{:stratum 0} limits
  "Small enough for any provider's declared ceiling."
  {:limits/cpu-millicores 500 :limits/memory-mib 512 :limits/ephemeral-storage-mib 1024})

(def ^{:stratum 0} exec-timeout-ms
  "Generous for a no-op command in a freshly provisioned environment."
  30000)

(def ^{:stratum 0} probe-path
  "A path writable in any environment image."
  "/tmp/conformance-probe.txt")

(def ^{:stratum 0} probe-text
  "Content a write then read must return unchanged."
  "conformance probe")

(defn ^{:stratum 0} acting-for [tenant-name now]
  (tenancy/establish-acting (tenancy/operator-identity tenant-name now) now))

(defn ^{:stratum 0} subtype? [reason result]
  (and (anomaly/anomaly? result) (= reason (anomaly/subtype result))))

(defn ^{:stratum 0} result [id passed? detail]
  {:check/id id
   :check/outcome (if passed? :check/passed :check/failed)
   :check/detail detail})

(defn ^{:stratum 0} memory-persister [lease path _text]
  {:artifact/path path :artifact/location (str (:lease/environment-uid lease) ":" path)})

(defn ^{:stratum 0} provision-grant [w acting]
  (let [scope {:environment/tenant-id (:acting/tenant-id acting)
               :environment/run-id (:run-id w)}]
    (grant/issue {:principal (str (:acting/principal-id acting))
                  :effect-class :effect/provision
                  :scope scope
                  :expires-at (:expires-at w)}
                 (:now w))))

(defn ^{:stratum 0} outcomes
  "Receipt outcomes for `operations`, in emission order."
  [w & operations]
  (let [wanted (set operations)]
    (mapv :receipt/outcome (filter #(wanted (:receipt/operation %)) @(:receipts w)))))

(def ^{:stratum 0} already-gone
  "Release refusals meaning the check itself already released that lease."
  #{:anomalies.agent-environment/environment-released :anomalies.agent-environment/stale-lease})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} probe-command
  "A command every environment image can run and exit zero."
  {:exec/argv ["true"] :exec/timeout-ms exec-timeout-ms})

(defn ^{:stratum 1} world
  "A fresh controller over `provider`, two tenants, and a receipt log."
  [provider isolation]
  (let [now (Instant/now)
        receipts (atom [])
        c (controller/create {:provider provider
                              :clock #(Instant/now)
                              :emit! #(swap! receipts conj %)
                              :persist! memory-persister})
        a (acting-for "conformance-tenant-a" now)
        b (acting-for "conformance-tenant-b" now)
        expires-at (.plusSeconds now grant-ttl-seconds)]
    {:c c
     :leases (atom [])
     :now now
     :expires-at expires-at
     :receipts receipts
     :isolation isolation
     :run-id (random-uuid)
     :a a
     :b b}))

(defn ^{:stratum 1} request
  ([w acting] (request w acting (random-uuid) (first template-hashes)))
  ([w acting task-id template-hash]
   {:environment/contract-version schema/contract-version
    :environment/tenant-id (:acting/tenant-id acting)
    :environment/principal-id (:acting/principal-id acting)
    :environment/run-id (:run-id w)
    :environment/task-id task-id
    :environment/template-hash template-hash
    :environment/policy-hash policy-hash
    :environment/isolation (:isolation w)
    :environment/limits limits
    :environment/expires-at (:expires-at w)}))

(defn ^{:stratum 1} acquire
  "Acquire and remember the lease, including the cleanup lease a failed
   provision returns, so `release-all!` can clean up."
  [w acting req]
  (let [env (lifecycle/acquire! (:c w) acting (provision-grant w acting) req)
        lease (or (:environment/lease env) (get-in env [:anomaly/data :environment/lease]))]
    (when lease (swap! (:leases w) conj [acting lease]))
    env))

(defn ^{:stratum 1} cleaned?
  "True when releasing `lease` confirmed it gone, or it was already gone."
  [w [acting lease]]
  (let [released (lifecycle/release! (:c w) acting lease)]
    (or (= :outcome/applied (:call/outcome released))
        (contains? already-gone (anomaly/subtype released)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} release-all!
  "Release every lease `w` acquired; true only when each is confirmed gone
   and no ledger entry is left anything but released, so a leak with no
   lease to retry still fails."
  [w]
  (let [released (mapv #(cleaned? w %) @(:leases w))
        entries (vals @(:controller/ledger (:c w)))]
    (and (every? true? released)
         (every? #(= :state/released (:entry/state %)) entries))))
