;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.task.graph
  "Task dependency graph implementation.
   Layer 0: Pure graph algorithms
   Layer 1: Graph data structure operations

   The dependency graph tracks which tasks depend on other tasks.
   A task can only start when all its dependencies are completed."
  (:require
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Pure graph algorithms

(defn dfs-visit
  "Depth-first search visit for cycle detection and topological sort.
   Returns {:visited visited :sorted sorted :cycle? cycle?}"
  [graph node visited temp-marks sorted]
  (cond
    (contains? temp-marks node)
    {:visited visited :sorted sorted :cycle? true :cycle-node node}

    (contains? visited node)
    {:visited visited :sorted sorted :cycle? false}

    :else
    (let [temp-marks' (conj temp-marks node)
          dependents (get graph node #{})
          result (reduce
                  (fn [acc dependent]
                    (if (:cycle? acc)
                      acc
                      (dfs-visit graph dependent (:visited acc) temp-marks' (:sorted acc))))
                  {:visited visited :sorted sorted :cycle? false}
                  dependents)]
      (if (:cycle? result)
        result
        {:visited (conj (:visited result) node)
         :sorted (cons node (:sorted result))
         :cycle? false}))))

(defn detect-cycle
  "Detect if adding an edge would create a cycle.
   Returns true if a cycle would be created."
  [graph from to]
  ;; A cycle is created if 'from' is reachable from 'to'
  (loop [queue [to]
         visited #{}]
    (if (empty? queue)
      false
      (let [node (first queue)
            rest-queue (rest queue)]
        (cond
          (= node from) true
          (contains? visited node) (recur rest-queue visited)
          :else (let [dependents (get graph node #{})]
                  (recur (into rest-queue dependents)
                         (conj visited node))))))))

(defn topological-sort-pure
  "Perform topological sort on a dependency graph.
   Returns a vector of task IDs in execution order, or nil if cycle detected.

   Arguments:
   - forward-deps: Map of task-id -> set of task-ids that depend on it"
  [forward-deps]
  (let [all-nodes (into #{} (concat (keys forward-deps)
                                    (apply concat (vals forward-deps))))
        result (reduce
                (fn [acc node]
                  (if (:cycle? acc)
                    acc
                    (dfs-visit forward-deps node (:visited acc) #{} (:sorted acc))))
                {:visited #{} :sorted '() :cycle? false}
                all-nodes)]
    (when-not (:cycle? result)
      (vec (:sorted result)))))

(defn find-ready-pure
  "Find all tasks with no unsatisfied dependencies.
   Pure function version.

   Arguments:
   - reverse-deps: Map of task-id -> set of task-ids this task depends on
   - completed: Set of completed task IDs"
  [reverse-deps completed]
  (->> (keys reverse-deps)
       (filter (fn [task-id]
                 ;; Task is ready if: not already completed AND dependencies are met
                 (and (not (completed task-id))
                      (let [deps (get reverse-deps task-id #{})]
                        (or (empty? deps)
                            (every? completed deps))))))
       set))

(defn find-blocked-pure
  "Find all tasks that have unsatisfied dependencies.
   Pure function version.

   Arguments:
   - reverse-deps: Map of task-id -> set of task-ids this task depends on
   - completed: Set of completed task IDs"
  [reverse-deps completed]
  (->> (keys reverse-deps)
       (filter (fn [task-id]
                 (let [deps (get reverse-deps task-id #{})]
                   (and (seq deps)
                        (not (every? completed deps))))))
       set))

;------------------------------------------------------------------------------ Layer 1
;; Graph data structure

(defn create-graph
  "Create a new dependency graph.
   The graph tracks both forward dependencies (what depends on what)
   and reverse dependencies (what each task depends on)."
  []
  (atom {:forward {}    ; task-id -> #{dependent task-ids}
         :reverse {}})) ; task-id -> #{dependency task-ids}

(defn add-dependency
  "Add a dependency: child depends on parent.
   The child task cannot start until the parent is completed.
   Returns true if dependency was added, false if it would create a cycle."
  ([graph parent-id child-id] (add-dependency graph parent-id child-id nil))
  ([graph parent-id child-id logger]
   (let [state @graph]
     ;; Check for cycle: if we add parent -> child edge,
     ;; a cycle exists if parent is already reachable from child
     (if (or (= parent-id child-id)
             (detect-cycle (:forward state) parent-id child-id))
       (do
         (when logger
           (log/warn logger :agent :task/cycle-detected
                     {:message "Dependency would create cycle"
                      :data {:parent-id parent-id
                             :child-id child-id}}))
         false)
       (do
         (swap! graph (fn [g]
                        (-> g
                            (update-in [:forward parent-id] (fnil conj #{}) child-id)
                            (update-in [:reverse child-id] (fnil conj #{}) parent-id))))
         (when logger
           (log/debug logger :agent :task/dependency-added
                      {:message "Dependency added"
                       :data {:parent-id parent-id
                              :child-id child-id}}))
         true)))))

(defn remove-dependency
  "Remove a dependency between parent and child."
  ([graph parent-id child-id] (remove-dependency graph parent-id child-id nil))
  ([graph parent-id child-id logger]
   (swap! graph (fn [g]
                  (-> g
                      (update-in [:forward parent-id] disj child-id)
                      (update-in [:reverse child-id] disj parent-id))))
   (when logger
     (log/debug logger :agent :task/dependency-removed
                {:message "Dependency removed"
                 :data {:parent-id parent-id
                        :child-id child-id}}))
   true))

(defn get-dependencies
  "Get all task IDs that the specified task depends on.
   These are tasks that must complete before this task can start."
  [graph task-id]
  (get-in @graph [:reverse task-id] #{}))

(defn get-dependents
  "Get all task IDs that depend on the specified task.
   These are tasks that cannot start until this task completes."
  [graph task-id]
  (get-in @graph [:forward task-id] #{}))

(defn has-dependency?
  "Check if child depends on parent."
  [graph parent-id child-id]
  (contains? (get-dependencies graph child-id) parent-id))

(defn register-task
  "Register a task in the graph without any dependencies.
   Useful for ensuring a task appears in graph queries."
  [graph task-id]
  (swap! graph (fn [g]
                 (-> g
                     (update :forward #(if (contains? % task-id) % (assoc % task-id #{})))
                     (update :reverse #(if (contains? % task-id) % (assoc % task-id #{})))))))

(defn unregister-task
  "Remove a task from the graph, including all its dependencies."
  [graph task-id]
  (let [state @graph
        deps (get-in state [:reverse task-id] #{})
        dependents (get-in state [:forward task-id] #{})]
    (swap! graph (fn [g]
                   (-> g
                       ;; Remove from forward deps of its dependencies
                       (update :forward
                               (fn [fwd]
                                 (reduce (fn [f dep]
                                           (update f dep disj task-id))
                                         (dissoc fwd task-id)
                                         deps)))
                       ;; Remove from reverse deps of its dependents
                       (update :reverse
                               (fn [rev]
                                 (reduce (fn [r dependent]
                                           (update r dependent disj task-id))
                                         (dissoc rev task-id)
                                         dependents))))))))

;------------------------------------------------------------------------------ Layer 2
;; Graph queries

(defn ready-tasks
  "Get all tasks that have no unsatisfied dependencies.
   Requires a set of completed task IDs.

   Arguments:
   - graph: The dependency graph
   - completed: Set of task IDs that are completed"
  [graph completed]
  (let [reverse-deps (:reverse @graph)]
    (find-ready-pure reverse-deps (set completed))))

(defn blocked-tasks
  "Get all tasks that have unsatisfied dependencies.

   Arguments:
   - graph: The dependency graph
   - completed: Set of task IDs that are completed"
  [graph completed]
  (let [reverse-deps (:reverse @graph)]
    (find-blocked-pure reverse-deps (set completed))))

(defn topological-sort
  "Return tasks in topological order (respecting dependencies).
   Tasks with no dependencies come first.
   Returns nil if the graph contains a cycle."
  [graph]
  (topological-sort-pure (:forward @graph)))

(defn dependency-chain
  "Get the full chain of dependencies for a task (transitive closure).
   Returns all tasks that must complete before this task can start."
  [graph task-id]
  (loop [queue (vec (get-dependencies graph task-id))
         visited #{}]
    (if (empty? queue)
      visited
      (let [current (first queue)
            rest-queue (rest queue)]
        (if (contains? visited current)
          (recur (vec rest-queue) visited)
          (let [deps (get-dependencies graph current)]
            (recur (into (vec rest-queue) deps)
                   (conj visited current))))))))

(defn dependent-chain
  "Get the full chain of dependents for a task (transitive closure).
   Returns all tasks that directly or indirectly depend on this task."
  [graph task-id]
  (loop [queue (vec (get-dependents graph task-id))
         visited #{}]
    (if (empty? queue)
      visited
      (let [current (first queue)
            rest-queue (rest queue)]
        (if (contains? visited current)
          (recur (vec rest-queue) visited)
          (let [deps (get-dependents graph current)]
            (recur (into (vec rest-queue) deps)
                   (conj visited current))))))))

(defn critical-path
  "Find the longest dependency chain in the graph.
   Returns a vector of task IDs representing the critical path."
  [graph]
  (let [sorted (topological-sort graph)]
    (when sorted
      (let [forward-deps (:forward @graph)
            ;; Calculate longest path to each node
            distances (reduce
                       (fn [dist node]
                         (let [node-dist (get dist node 0)
                               dependents (get forward-deps node #{})]
                           (reduce (fn [d dependent]
                                     (update d dependent (fnil max 0) (inc node-dist)))
                                   dist
                                   dependents)))
                       {}
                       sorted)
            ;; Find the end of the critical path
            end-node (first (apply max-key #(get distances (first %) 0) distances))]
        ;; Reconstruct the path
        (when end-node
          (loop [path [end-node]
                 current end-node]
            (let [deps (get-dependencies graph current)
                  prev (first (sort-by #(get distances % 0) > deps))]
              (if prev
                (recur (cons prev path) prev)
                (vec path)))))))))

(defn graph-stats
  "Return statistics about the dependency graph."
  [graph]
  (let [state @graph
        forward (:forward state)
        reverse (:reverse state)
        all-nodes (into #{} (concat (keys forward) (keys reverse)))
        edge-count (reduce + (map count (vals forward)))]
    {:node-count (count all-nodes)
     :edge-count edge-count
     :roots (count (filter #(empty? (get reverse % #{})) all-nodes))
     :leaves (count (filter #(empty? (get forward % #{})) all-nodes))}))

(defn clear-graph
  "Remove all tasks and dependencies from the graph."
  [graph]
  (reset! graph {:forward {} :reverse {}}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a dependency graph
  (def g (create-graph))

  ;; Add some tasks and dependencies
  ;; Task structure: plan -> design -> implement -> test
  (def task-a (random-uuid))
  (def task-b (random-uuid))
  (def task-c (random-uuid))
  (def task-d (random-uuid))

  (register-task g task-a)
  (register-task g task-b)
  (register-task g task-c)
  (register-task g task-d)

  (add-dependency g task-a task-b)  ; b depends on a
  (add-dependency g task-b task-c)  ; c depends on b
  (add-dependency g task-c task-d)  ; d depends on c

  ;; Check dependencies
  (get-dependencies g task-c)  ; => #{task-b}
  (get-dependents g task-a)    ; => #{task-b}

  ;; Find ready tasks (none completed yet)
  (ready-tasks g #{})          ; => #{task-a}

  ;; After completing task-a
  (ready-tasks g #{task-a})    ; => #{task-b}

  ;; Topological sort
  (topological-sort g)         ; => [task-a task-b task-c task-d]

  ;; Cycle detection
  (add-dependency g task-d task-a)  ; Would create cycle, returns false

  ;; Full dependency chain
  (dependency-chain g task-d)  ; => #{task-a task-b task-c}

  ;; Graph stats
  (graph-stats g)

  :leave-this-here)
