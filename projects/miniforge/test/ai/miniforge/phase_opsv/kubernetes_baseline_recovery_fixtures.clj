;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-recovery-fixtures
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-fixtures :as restoration]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} with-input restoration/with-input)

(def ^{:stratum 0} absent-resource
  "An omitted wire route produces a real HTTP 404, not a JSON-null response."
  ::absent-resource)

(defn ^{:stratum 0} outside-recovery-count [fixture]
  (inc (get-in fixture [:input :load :request :comparison/pin :comparison/contract
                        :comparison/replica-contract :rollback :replicas/max-count])))

(defn ^{:stratum 0} template-image-routes [fixture image]
  (assoc-in (get-in fixture [:native :routes])
            [inventory/deployment-path "spec" "template" "spec" "containers" 0 "image"] image))

(defn ^{:stratum 0} drift-events [{:keys [ctx]}]
  (filterv #(= :opsv.drift/detected (:event/type %)) (events/get-events (:event-stream ctx))))

(defn ^{:stratum 0} verify [{:keys [ctx] :as fixture} routes]
  (let [calls (atom [])
        input (dissoc (:restoration/request fixture) :effect/id)]
    (with-redefs [transport/start! (partial wire/reply! routes calls) transport/response-fields identity]
      [(phase/verify-kubernetes-baseline-recovery! ctx input) @calls])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} changed-routes [path value fixture]
  (let [routes (get-in fixture [:native :routes])]
    (if (= absent-resource value) (dissoc routes (first path)) (assoc-in routes path value))))

(comment
  (with-input #(verify % (get-in % [:native :routes]))))
