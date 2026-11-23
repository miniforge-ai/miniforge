;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-effect-authorization
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as admission]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} call! [current-context! runtime issued ready record operation]
  (anomaly/let-ok [ctx (current-context! runtime)
                  current (grant/current (:authority-directory runtime) (:grant/id issued))]
    (if (and (= (admission/snapshot ctx) (admission/snapshot (:context ready)))
             current
             (grant/authorized? (grant/authorize current {:effect/scope (:effect/proposal record)
                                                        :usage/count 1}
                                                ((:clock runtime)))))
      (operation)
      (admission/refused))))

(comment ::one-exact-authority-check-with-code-owned-current-admission)
