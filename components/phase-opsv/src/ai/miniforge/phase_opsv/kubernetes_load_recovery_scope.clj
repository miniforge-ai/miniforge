;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-recovery-scope
  (:require [ai.miniforge.phase-opsv.kubernetes-load-cleanup :as cleanup]
            [ai.miniforge.phase-opsv.kubernetes-load-stop :as stop]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} operations
  {:stop-load [stop/request stop/ready?]
   :cleanup-load [cleanup/request cleanup/ready?]})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} matches? [proposal plan owned]
  (let [scope (:opsv/experiment proposal)
        [request permitted?] (get operations (:opsv/operation scope))
        input {:effect/id (:effect/id proposal)}
        uid (get-in owned [:load/pod "metadata" "uid"])
        resource (assoc (get-in plan [:recovery/load :resource]) :uid uid)]
    (and request
         (= :effect/load-test (:effect/class proposal))
         (= [resource] (:opsv/resources scope))
         (permitted? input plan owned)
         (= (request owned) (:kubernetes/request proposal)))))

(comment ::reuse-canonical-request-factories-for-current-uid-revision-and-quiescence-admission)
