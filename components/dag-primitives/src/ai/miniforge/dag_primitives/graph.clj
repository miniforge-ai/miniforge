(ns ai.miniforge.dag-primitives.graph
  "Utility functions for directed graphs represented as dependency maps.
   dep-map convention: {node-id #{predecessor-id ...}}")

;;------------------------------------------------------------------------------ Layer 0
;; Graph construction

(defn successors-of
  "Build a forward adjacency map from a dependency map.
   For each node, returns the set of nodes that directly follow it.
   Every node in dep-map gets an entry, even if it has no successors."
  [dep-map]
  (reduce (fn [adj [node predecessors]]
            (reduce (fn [adj predecessor]
                      (update adj predecessor (fnil conj #{}) node))
                    adj
                    predecessors))
          (zipmap (keys dep-map) (repeat #{}))
          dep-map))

(defn predecessor-counts
  "Return a map of {node-id count} counting unsatisfied predecessors per node."
  [dep-map]
  (reduce-kv (fn [counts node predecessors]
               (assoc counts node (count predecessors)))
             {}
             dep-map))
