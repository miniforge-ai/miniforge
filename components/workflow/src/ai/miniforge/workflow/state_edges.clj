;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-edges
  "Transition-target graph construction.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} transition-targets
  "Return the set of target state-ids referenced by a single transition
   value. Handles the three transition shapes clj-statecharts accepts:
   bare keyword (target), single map (`:target` slot), and ordered
   vector of maps (guarded array — every map's `:target` is a reachable
   edge, since first matching guard wins but ANY of them MAY fire)."
  [transition]
  (cond
    (keyword? transition)    #{transition}
    (map? transition)        (some-> (:target transition) hash-set)
    (sequential? transition) (into #{} (comp (filter map?) (keep :target)) transition)
    :else                    #{}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} state-transition-targets
  [state-config]
  (->> (get state-config :on {})
       vals
       (mapcat transition-targets)
       (filter some?)
       set))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} build-state-graph
  [states]
  (zipmap (keys states) (map state-transition-targets (vals states))))

(comment ::workflow-state-language)
