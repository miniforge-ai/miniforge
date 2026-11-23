;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.effect-authority
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} revoke! [runtime issued now]
  ;; Registration can publish the grant before an interrupted durability check refuses it.
  (let [interrupted? (Thread/interrupted)]
    (try+ (grant/revoke-stored! (:authority-directory runtime) (:grant/id issued) :revocation/superseded now)
      (finally (when interrupted? (.interrupt (Thread/currentThread)))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} abandon! [runtime issued now result]
  (if-not (anomaly/anomaly? result)
    result
    (let [revoked (revoke! runtime issued now)]
      (cond-> (assoc-in result [:anomaly/data :grant/id] (:grant/id issued))
        (anomaly/anomaly? revoked) (assoc-in [:anomaly/data :grant/revocation-failure] revoked)))))

(comment
  (abandon! {} {} nil {:result :unchanged}))
