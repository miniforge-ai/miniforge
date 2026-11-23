;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-recovery-plan
  "Pretrial recovery intent; never retain a stale restoration payload or invent a load UID."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.kubernetes-load-request :as load]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-replica-request :as replica]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} restore-intent [restoration original]
  (let [resource (first (:opsv/resources restoration))
        count (get-in original [:kubernetes/inventory :kubernetes/deployment "spec" "replicas"])]
    {:resource resource
     :replicas/count count
     :preconditions :fresh-uid-and-resource-version}))

(defn- ^{:stratum 0} load-intent [load]
  (let [id (:effect/id load)
        binding (:opsv/experiment load)
        digest (:opsv/payload-hash binding)
        resource (first (:opsv/resources binding))]
    {:creation-effect/id id
     :creation-payload/hash digest
     :resource resource
     :ownership :durable-creation-receipt-and-live-uid
     :termination :observed-container-termination
     :cleanup-preconditions :fresh-uid-and-resource-version}))

(defn- ^{:stratum 0} plan-binding [input]
  (let [native (:load input)
        request (:request native)
        pin (:comparison/pin request)
        attempt (get-in pin [:comparison/contract :comparison/binding])
        reference (:comparison/artifact-ref native)
        digest (:comparison/hash pin)
        context (get-in native [:limits :kubernetes/context])
        duration (:recovery/duration-ms input)
        max-effects (count schema/operations)]
    {:recovery/binding attempt
     :comparison/artifact-ref reference
     :comparison/hash digest
     :kubernetes/context context
     :recovery/duration-ms duration
     :recovery/operations schema/operations
     :recovery/max-effects max-effects}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} plan [input load restoration original]
  (let [bound (plan-binding input)
        restore (restore-intent restoration original)
        load (load-intent load)]
    (assoc bound :recovery/restore restore :recovery/load load)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [ctx input baseline]
  (let [native (:load input)
        original (get-in baseline [:environment/raw-observations :kubernetes/verification :kubernetes/source])]
    (anomaly/let-ok [load (load/prepare ctx native baseline)
                    restoration (replica/prepare (:declaration native) (:request native) baseline original
                                                  {:opsv/operation :restore-baseline})]
      (let [value (plan input load restoration original)
            digest (hash/content-hash value)]
        {:recovery/plan value :recovery/hash digest}))))

(comment ::restoration-count-comes-only-from-the-retained-original)
