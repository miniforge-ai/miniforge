;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-trial-execution-fault-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as capture]
            [ai.miniforge.phase-opsv.kubernetes-effect-publication-fault-fixtures :as publication]
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

(defn- ^{:stratum 0} changed-response! [ready? path value reply! fixture routes calls options]
  (let [current (if (ready? fixture) (assoc-in routes path value) routes)]
    (reply! current calls options)))

(defn ^{:stratum 0} execute-with-publication-failure [fixture]
  (publication/execute fixtures/execute :native-replica-trial-readiness fixture))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute-with-drift [path value fixture]
  (let [reply! capture/reply!]
    (with-redefs [capture/reply! (partial changed-response! admitted? path value reply! fixture)]
      (fixtures/execute fixture))))

(defn ^{:stratum 1} execute-with-revision-change [fixture]
  (let [reply! capture/reply!
        path [inventory/deployment-path "metadata" "resourceVersion"]]
    (with-redefs [capture/reply! (partial changed-response! claimed? path concurrent-revision reply! fixture)]
      (fixtures/execute fixture))))

(comment ::fault-injection-and-observation-delegate-to-the-real-producers)
