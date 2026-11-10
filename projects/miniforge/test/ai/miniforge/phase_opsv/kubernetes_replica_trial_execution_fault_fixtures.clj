;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-fault-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-readiness :as readiness]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as capture]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-fixtures :as fixtures]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} concurrent-revision
  "A benign resource update still changes the exact conditional patch payload."
  "concurrently-updated-revision")

(defn- ^{:stratum 0} admitted? [fixture]
  (= :committing (:opsv/effect-state (last (forward/dispositions fixture)))))

(defn- ^{:stratum 0} claimed? [{:keys [host/runtime request]}]
  (= :committing (:effect/state (effect/read-record (:effects-directory runtime) (:effect/id request)))))

(defn- ^{:stratum 0} refuse-publication! [captured value]
  (reset! captured value)
  (anomaly/anomaly :unavailable "injected readiness publication refusal" {}))

(defn- ^{:stratum 0} capture-retention! [captured retain! operation ready record]
  (let [raw (#'readiness/content operation ready record)
        result (retain! operation ready record)]
    (reset! captured {:raw raw
                     :result result})
    result))

(defn- ^{:stratum 0} changed-response! [ready? path value reply! fixture routes calls options]
  (let [current (if (ready? fixture) (assoc-in routes path value) routes)]
    (reply! current calls options)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} fail-readiness! [captured publish! directory value]
  (if (= :native-replica-trial-readiness (get-in value [:artifact/metadata :opsv/material-kind]))
    (refuse-publication! captured value)
    (publish! directory value)))

(defn ^{:stratum 1} execute-with-drift [path value fixture]
  (let [reply! capture/reply!]
    (with-redefs [capture/reply! (partial changed-response! admitted? path value reply! fixture)]
      (fixtures/execute fixture))))

(defn ^{:stratum 1} execute-with-revision-change [fixture]
  (let [reply! capture/reply!
        path [inventory/deployment-path "metadata" "resourceVersion"]]
    (with-redefs [capture/reply! (partial changed-response! claimed? path concurrent-revision reply! fixture)]
      (fixtures/execute fixture))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-with-publication-failure [fixture]
  (let [value (atom nil)
        observed (atom nil)
        publish! artifact/publish!
        retain! readiness/retain!]
    (with-redefs [artifact/publish! (partial fail-readiness! value publish!)
                  readiness/retain! (partial capture-retention! observed retain!)]
      (let [[result calls] (fixtures/execute fixture)]
        {:result result
         :calls calls
         :artifact @value
         :retention @observed}))))

(comment ::fault-injection-and-observation-delegate-to-the-real-producers)
