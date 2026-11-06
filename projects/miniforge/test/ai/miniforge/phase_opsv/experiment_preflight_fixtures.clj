;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.experiment-preflight-fixtures
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.governance-fixtures :as governance]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-preparation-fixtures :as native]
            [ai.miniforge.phase-opsv.test-support :as support]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} repack [input pack]
  (let [request (:request input)
        digest (hash/content-hash pack)
        contract (assoc-in (get-in request [:comparison/pin :comparison/contract])
                           [:comparison/binding :experiment-pack/hash] digest)
        pin (phase/pin-verification-environment contract)]
    (assoc input :request (assoc request :experiment-pack pack :experiment-pack/hash digest :comparison/pin pin))))

(defn- ^{:stratum 0} configured-policy [namespace]
  (-> governance/policy
      (assoc-in [:policy/context :opsv/blast-radius-limits :allowed-namespaces] #{namespace})
      (assoc-in [:policy/context :opsv/apply-enabled?] false)))

(defn- ^{:stratum 0} runtime-settings [declaration]
  {:experiment/enabled? true
   :environment/class :staging
   :experiment/environment "staging"
   :experiment/declaration declaration})

(defn ^{:stratum 0} evaluate [{:keys [ctx request baseline]}]
  (phase/evaluate-experiment-preflight ctx request baseline))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} context [input]
  (let [declaration (:declaration input)
        pack (get-in input [:request :experiment-pack])
        namespace (get-in declaration [:runtime/target :namespace])
        configured (configured-policy namespace)
        settings (runtime-settings declaration)]
    (-> (support/execution-context nil)
        (assoc :execution/id (:workflow/id declaration))
        (assoc-in [:execution/input :opsv/experiment-pack] pack)
        (assoc-in [:execution/opts :opsv/governance] configured)
        (assoc-in [:execution/opts :opsv/experiment-execution] settings))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} input
  ([] (input identity))
  ([transform]
   (let [original (native/input)
         namespace (get-in original [:declaration :runtime/target :namespace])
         pack (assoc-in (get-in original [:request :experiment-pack])
                         [:experiment-pack/guardrails :blast-radius :namespaces] [namespace])
         bounded (repack original (transform pack))
         [baseline calls] (native/observe-transport (:routes bounded) bounded)]
     (assoc bounded :ctx (context bounded) :baseline baseline :http-calls calls))))

(comment ::real-preparation-and-gate-engine-only-http-transport-is-simulated)
