;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.alert-outcomes
  "Pure bounded outcome history and completed-call transition.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private window-size 100)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} append-outcome [outcomes outcome]
  (let [appended (conj (vec outcomes) outcome)
        offset (max 0 (- (count appended) window-size))]
    (subvec appended offset)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} complete-call [state event]
  (cond-> (update state :outstanding-calls dissoc (:tool/call-id event))
    (contains? event :tool/success?)
    (update :tool-outcomes append-outcome (:tool/success? event))))

(comment ::bounded-outcome-transition)
