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
(ns ai.miniforge.agent-worker.fixtures
  "A leased fake environment, provision and spend grants, and a worker
   controller over the given workers."
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-worker.interface :as worker]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.tenancy.interface :as tenancy])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ttl-seconds
  "Long enough that no test outlives its grants or environment."
  3600)

(def ^{:stratum 0} workdir "/work")

(def ^{:stratum 0} sample-hash (apply str (repeat 64 "a")))

(def ^{:stratum 0} limits
  {:limits/cpu-millicores 500 :limits/memory-mib 512 :limits/ephemeral-storage-mib 1024})

(defn ^{:stratum 0} acting-for [tenant-name now]
  (tenancy/establish-acting (tenancy/operator-identity tenant-name now) now))

(defn- ^{:stratum 0} collect! [receipts receipt] (swap! receipts conj receipt))

(defn- ^{:stratum 0} store-artifact [lease path _text]
  {:artifact/path path :artifact/location (str (:lease/environment-uid lease) ":" path)})

(defn ^{:stratum 0} submission
  [w cfg attempt prompt]
  {:submission/binding (worker/bind (:acting w) (:lease w) (:spend w) cfg attempt)
   :submission/config cfg
   :submission/prompt prompt})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} environment-request [acting run-id expires-at]
  {:environment/contract-version env/contract-version
   :environment/tenant-id (:acting/tenant-id acting)
   :environment/principal-id (:acting/principal-id acting)
   :environment/run-id run-id
   :environment/task-id (random-uuid)
   :environment/template-hash sample-hash
   :environment/policy-hash sample-hash
   :environment/isolation :isolation/shared-kernel
   :environment/limits limits
   :environment/expires-at expires-at})

(defn ^{:stratum 1} issue [acting effect-class scope constraints now]
  (grant/issue {:principal (str (:acting/principal-id acting))
                :effect-class effect-class
                :scope scope
                :constraints constraints
                :expires-at (.plusSeconds ^Instant now ttl-seconds)}
               now))

(defn ^{:stratum 1} config [worker-kind & {:as extra}]
  (merge {:config/worker worker-kind :config/workdir workdir} extra))

(defn ^{:stratum 1} start!
  [w cfg attempt prompt]
  (worker/start! (:wc w) (:acting w) (:spend w) (:lease w) (submission w cfg attempt prompt)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} world
  "A provisioned fake environment (built with `provider-opts`) and a worker
   controller over `workers`; the spend grant carries `spend-constraints`."
  [workers spend-constraints provider-opts]
  (let [now (Instant/now)
        provider (env/fake-provider provider-opts)
        acting (acting-for "worker-tenant" now)
        run-id (random-uuid)
        scope {:environment/tenant-id (:acting/tenant-id acting) :environment/run-id run-id}
        ec (env/create-controller {:provider provider
                                   :clock #(Instant/now)
                                   :emit! (partial collect! (atom []))
                                   :persist! store-artifact})
        request (environment-request acting run-id (.plusSeconds now ttl-seconds))
        environment (env/acquire! ec acting (issue acting :effect/provision scope {} now) request)
        spend (issue acting :effect/spend {:worker/tenant-id (:acting/tenant-id acting) :worker/run-id run-id}
                     spend-constraints now)
        wc (worker/create-controller {:workers workers :environment ec :clock #(Instant/now)})]
    {:now now
     :provider provider
     :acting acting
     :ec ec
     :wc wc
     :request request
     :provision-scope scope
     :lease (:environment/lease environment)
     :namespace (:environment/namespace environment)
     :spend spend}))
