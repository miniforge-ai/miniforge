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

(defn ^{:stratum 0} changed-routes [path value fixture]
  (assoc-in (get-in fixture [:native :routes]) path value))

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

(comment
  (with-input #(verify % (get-in % [:native :routes]))))
