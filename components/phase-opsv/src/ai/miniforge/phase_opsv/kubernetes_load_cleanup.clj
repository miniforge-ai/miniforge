;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-cleanup
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-proposal :as proposal]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} cleanup-propagation-policy
  "Only the Pod UID is proven owned; never cascade deletion into unproven dependents." "Orphan")

(defn- ^{:stratum 0} usable? [input plan owned]
  (and (not= (:effect/id input) (get-in plan [:recovery/load :creation-effect/id]))
       (contains? #{:not-started :terminated} (:load/quiescence owned))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} request [owned]
  (let [metadata (get-in owned [:load/pod "metadata"])
        collection (get-in owned [:load/creation-record :effect/proposal :kubernetes/request :path])
        path (str collection "/" (get metadata "name"))
        uid (get metadata "uid")
        revision (get metadata "resourceVersion")
        preconditions {"uid" uid "resourceVersion" revision}
        body {"apiVersion" "v1" "kind" "DeleteOptions"
              "preconditions" preconditions "propagationPolicy" cleanup-propagation-policy}]
    {:method :delete :path path :media-type "application/json" :body body}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [ctx input plan owned]
  (if-not (usable? input plan owned)
    (anomaly/anomaly :conflict (msg/ts :load/cleanup-request-refused) {})
    (let [payload (request owned)
          intent {:opsv/operation :cleanup-resource :kubernetes/request payload}]
      (proposal/proposal ctx input plan owned intent))))

(comment ::quiescence-precedes-cleanup-authority-and-acknowledgement-does-not-prove-deletion)
