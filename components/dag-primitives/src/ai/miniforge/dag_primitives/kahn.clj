(ns ai.miniforge.dag-primitives.kahn
  "Generic Kahn's algorithm for topological sort.
   Operates on a plain dependency map — no domain types."
  (:require [clojure.set :as set]))

;;------------------------------------------------------------------------------ Layer 0
;; Topological sort

(defn topological-sort
  "Kahn's algorithm on a generic dependency map.

   dep-map: {node-id #{predecessor-node-id ...}}
     Each key is a node; its value is the set of nodes that must precede it.
     Nodes with an empty (or absent) predecessor set have no dependencies.

   ordered-nodes (optional): sequence of all nodes in preferred initial-queue order.
     When two nodes are both ready at the same time, they are dequeued in the order
     they appear in ordered-nodes. If omitted, (keys dep-map) order is used.
     Providing an explicit ordered-nodes makes tie-breaking deterministic.

   Returns:
     {:ok? true  :data [node-ids in topological order]}
     {:ok? false :error {:code :cycle-detected :cycle-nodes #{...}}}"
  ([dep-map]
   (topological-sort dep-map (keys dep-map)))
  ([dep-map ordered-nodes]
   (let [all-nodes  (set (keys dep-map))
         ;; Build forward adjacency: predecessor → #{successors}
         adj        (reduce (fn [a [node preds]]
                               (reduce (fn [a2 pred] (update a2 pred (fnil conj #{}) node))
                                       a
                                       preds))
                             (zipmap all-nodes (repeat #{}))
                             dep-map)
         ;; in-degree: number of unsatisfied predecessors per node
         in-degree  (reduce-kv (fn [m node preds] (assoc m node (count preds)))
                                {}
                                dep-map)]
     (loop [queue  (into clojure.lang.PersistentQueue/EMPTY
                         (filter #(zero? (get in-degree %)) ordered-nodes))
            in-deg in-degree
            result []]
       (if (empty? queue)
         (if (= (count result) (count all-nodes))
           {:ok? true :data result}
           (let [remaining (set/difference all-nodes (set result))]
             {:ok? false :error {:code :cycle-detected :cycle-nodes remaining}}))
         (let [node        (peek queue)
               neighbors   (get adj node #{})
               new-deg     (reduce #(update %1 %2 dec) in-deg neighbors)
               ;; Enqueue newly-ready neighbors in ordered-nodes order for stability
               newly-ready (filter #(zero? (get new-deg %)) neighbors)]
           (recur (into (pop queue) newly-ready)
                  new-deg
                  (conj result node))))))))
