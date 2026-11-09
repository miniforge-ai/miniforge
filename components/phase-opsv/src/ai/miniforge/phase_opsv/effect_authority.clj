;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.effect-authority
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} abandon! [runtime issued now result]
  (if-not (anomaly/anomaly? result)
    result
    (let [revoked (grant/revoke-stored! (:authority-directory runtime)
                                       (:grant/id issued) :revocation/superseded now)]
      (cond-> (assoc-in result [:anomaly/data :grant/id] (:grant/id issued))
        (anomaly/anomaly? revoked) (assoc-in [:anomaly/data :grant/revocation-failure] revoked)))))

(comment
  (abandon! {} {} nil {:result :unchanged}))
