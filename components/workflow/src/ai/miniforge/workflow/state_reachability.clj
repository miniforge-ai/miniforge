;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-reachability
  "Reachability of compiled workflow state graphs."
  (:require [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} bfs-reachable-states [state-graph start-state]
  (loop [to-visit [start-state] visited #{}]
    (let [state (first to-visit)
          remaining (rest to-visit)]
      (cond
        (empty? to-visit) visited
        (contains? visited state) (recur remaining visited)
        :else (recur (concat remaining (get state-graph state #{}))
                     (conj visited state))))))

(defn ^{:stratum 0} state-graph
  "Return the compiled state graph for a workflow execution machine."
  [machine]
  (:workflow/state-graph machine))

(defn ^{:stratum 0} compiled-state-ids
  "Return the set of states defined by a compiled workflow execution machine."
  [machine]
  (:workflow/state-ids machine))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} reachable-states
  "Return the set of states reachable in a compiled workflow machine.

   With one arg, traversal starts from the machine's initial state.
   With two args, traversal starts from the provided state id."
  ([machine]
   (reachable-states machine (:workflow/initial-state machine)))
  ([machine start-state]
   (if start-state
     (bfs-reachable-states (state-graph machine) start-state)
     #{})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} unreachable-states
  "Return the set of defined states that are not reachable in a compiled workflow machine."
  ([machine]
   (unreachable-states machine (:workflow/initial-state machine)))
  ([machine start-state]
   (set/difference (compiled-state-ids machine)
                   (reachable-states machine start-state))))

(comment ::workflow-state-language)
