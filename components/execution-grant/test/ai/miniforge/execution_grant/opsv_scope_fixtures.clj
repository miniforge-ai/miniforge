;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.opsv-scope-fixtures
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.execution-grant.interface :as grant])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-11-04T00:00:00Z"))

(def ^{:stratum 0} expiry (Instant/parse "2026-11-04T00:10:00Z"))

(def ^{:stratum 0} workflow-id #uuid "b4bd9e4f-57ac-40fb-a724-26f4619a47fe")

(def ^{:stratum 0} effect-id #uuid "0ccdfbe7-cc0b-4f0f-a8a5-23fb6aa7e061")

(defn ^{:stratum 0} digest [value] (hash/content-hash value))

(defn ^{:stratum 0} target []
  {:cluster "staging-cluster"
   :namespace "staging"
   :namespace-uid "namespace-uid"
   :workload-uid "deployment-uid"})

(defn ^{:stratum 0} resource [operation]
  (let [deployment? (contains? #{:trial-apply :restore-baseline} operation)
        kind (if deployment? "Deployment" "Job")
        version (if deployment? "apps/v1" "batch/v1")
        name (if deployment? "catalog" "catalog-opsv-load")
        uid (get {:trial-apply "deployment-uid"
                  :restore-baseline "deployment-uid"
                  :stop-load "load-job-uid"
                  :cleanup-load "load-job-uid"} operation)]
    {:api-version version
     :kind kind
     :namespace "staging"
     :name name
     :uid uid}))

(defn ^{:stratum 0} limits []
  {:load/max-requests 100
   :load/max-concurrency 2
   :load/max-rate-per-second 10})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} options [class scope]
  (let [principal (str "runtime:workflow-run/" workflow-id)]
    {:principal principal
     :effect-class class
     :scope scope
     :constraints {:constraint/max-count 1}
     :delegable? false
     :expires-at expiry}))

;; N6 UUID references follow candidate-verification/measure; target follows native inventory.
(defn ^{:stratum 1} experiment-binding [operation]
  (let [target (target)
        resources [(resource operation)]
        limits (limits)
        candidate (hash/content-hash :candidate)
        pack (hash/content-hash :experiment-pack)
        comparison (hash/content-hash :comparison)
        payload (hash/content-hash operation)]
    {:verification/id #uuid "04518acb-33e7-466a-93d9-3042f0b73499"
     :candidate/hash candidate
     :experiment-pack/hash pack
     :comparison/artifact-ref #uuid "c512c548-e350-4010-9dd9-53b6f70fe1a1"
     :comparison/hash comparison
     :environment/class :staging
     :runtime/target target
     :kubernetes/context "staging-context"
     :kubernetes/deployment-name "catalog"
     :opsv/operation operation
     :opsv/payload-hash payload
     :opsv/resources resources
     :opsv/traffic-destinations ["https://catalog.staging.svc.cluster.local/"]
     :opsv/duration-ms 60000
     :opsv/load-limits limits}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} issued
  ([class scope] (issued class scope {}))
  ([class scope overrides]
   (grant/issue (merge (options class scope) overrides) now)))

(defn ^{:stratum 2} scope [operation]
  (let [binding (experiment-binding operation)]
    {:workflow-run/id workflow-id
     :effect/id effect-id
     :opsv/experiment binding}))

(comment ::hashes-use-canonical-producer-target-mirrors-native-inventory-identity)
