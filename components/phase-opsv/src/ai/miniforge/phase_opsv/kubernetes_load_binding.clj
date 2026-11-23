;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-binding
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.governed-experiment-schema :as experiment]
            [ai.miniforge.phase-opsv.kubernetes-effect-binding :as native]
            [ai.miniforge.phase-opsv.kubernetes-load-pod :as pod]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} quantity [value unit]
  (when (string? value)
    (some-> (re-matches (re-pattern (str "([0-9]+)" unit)) value) second parse-long)))

(defn ^{:stratum 0} matches? [settings record input]
  (let [proposal (:effect/proposal record)
        scope (:opsv/experiment proposal)
        expected (pod/request input)]
    (and (= (:effect/id record) (:effect/id proposal))
         (native/configured-target? settings scope)
         (= [(pod/resource-reference input)] (:opsv/resources scope))
         (= expected (native/request record))
         (= (hash/content-hash expected) (:opsv/payload-hash scope)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} input [record]
  (let [proposal (:effect/proposal record)
        scope (:opsv/experiment proposal)
        worker (get-in (native/request record) [:body "spec" "containers" 0])
        cpu (quantity (get-in worker ["resources" "limits" "cpu"]) "m")
        memory (quantity (get-in worker ["resources" "limits" "memory"]) "Mi")]
    {:declaration {:workflow/id (:workflow-run/id proposal) :runtime/target (:runtime/target scope)}
     :request {:verification/id (:verification/id scope)}
     :limits (assoc (select-keys scope experiment/runtime-bound-keys) :effect/id (:effect/id proposal))
     :runtime {:load/image (get worker "image") :load/cpu-millis cpu :load/memory-mib memory}}))

(comment ::reconstruct-the-sole-supported-wire-payload-not-arbitrary-pod-or-http-input)
