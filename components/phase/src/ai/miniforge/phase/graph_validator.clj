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

(ns ai.miniforge.phase.graph-validator
  "Seven structural property checks on a TransitionGraph.

   PUBLIC API:
     validate-graph  — takes a TransitionGraph + options map, returns
                       {:valid? bool :errors [anomaly...] :warnings [anomaly...]}
     valid?          — takes a TransitionGraph, returns boolean (convenience)

   INDIVIDUAL CHECKS (composable; each returns [anomaly]):
     check-existence      — Property 1: dangling edge targets
     check-termination    — Property 2: BFS reachability to terminal from every node
     check-cycles         — Property 3: DFS back-edge detection; runaway cycles flagged
     check-retry-bounds   — Property 4: retry self-loops must have iteration bound + exhaustion exit
     check-failure-paths  — Property 5: every phase has a failure edge reaching terminal
     check-budget-paths   — Property 6: budget-guarded phases have :on-budget-exhausted → terminal
     check-interceptors   — Property 7 (opt-in): every phase has a registered interceptor

   bb-loadable: no JVM-only deps; Property 7 is gated on :check-interceptors? true and
   requires an :interceptor-registered-fn injected by the caller."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.graph :as phase-graph]
   [ai.miniforge.phase.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Named constants

(def failure-edge-labels
  "Edge labels that represent failure or escape paths out of a phase (Property 5).
   At least one edge with one of these labels must reach a terminal from every
   non-terminal phase node."
  #{:on-failure :on-budget-exhausted :redirect})

;------------------------------------------------------------------------------ Layer 0
;; Pure graph query helpers

(defn- build-adjacency
  "Build a map of from-node → [to-node ...] from a vector of graph edges.
   Used for BFS termination checks and DFS cycle detection."
  [edges]
  (reduce (fn [acc {:keys [from to]}]
            (update acc from (fnil conj []) to))
          {}
          edges))

(defn- index-edges-by-from
  "Build a map of from-node → [edge ...] for all edges.
   Provides direct access to labeled edges for per-node checks."
  [edges]
  (reduce (fn [acc edge]
            (update acc (:from edge) (fnil conj []) edge))
          {}
          edges))

(defn- bfs-reaches-terminal?
  "Returns true when any terminal node is reachable from `start` by BFS.
   The start node itself counts: a terminal that starts the search trivially
   satisfies reachability. Self-loops are handled by the visited set."
  [adjacency terminal-nodes start]
  (loop [queue (list start) visited #{}]
    (cond
      (empty? queue)
      false
      (contains? terminal-nodes (first queue))
      true
      :else
      (let [curr      (first queue)
            visited'  (conj visited curr)
            neighbors (remove visited' (get adjacency curr []))]
        (recur (concat (rest queue) neighbors) visited')))))

;------------------------------------------------------------------------------ Layer 0
;; DFS cycle detection

(defn- path-index-of
  "Returns the first index i where (nth path-vec i) equals `node`, or nil."
  [path-vec node]
  (some (fn [[i n]] (when (= n node) i))
        (map-indexed vector path-vec)))

(defn- record-cycle-if-present!
  "When `neighbor` is on the current gray DFS path, record the cycle nodes.
   Extracts the subvec from neighbor's position to the end of the path and
   conjoins it as a set into `cycles`."
  [cycles path neighbor]
  (let [path-vec  @path
        cycle-idx (path-index-of path-vec neighbor)]
    (when cycle-idx
      (swap! cycles conj (set (subvec path-vec cycle-idx))))))

(defn- find-cycles
  "Recursive DFS with white/gray/black coloring. Returns a vector of
   cycle-node-sets (each set contains all nodes on the detected cycle).
   Self-loops produce single-node sets #{node}.
   Safe for workflow-sized graphs (≤ 100 nodes); recursion depth ≤ node count."
  [adjacency nodes]
  (let [colors (atom {})    ; nil = white, :gray = in-progress, :black = done
        path   (atom [])    ; current DFS path (gray nodes in order)
        cycles (atom [])]
    (letfn [(dfs [node]
              (when (nil? (get @colors node))
                (swap! colors assoc node :gray)
                (swap! path conj node)
                (doseq [neighbor (get adjacency node [])]
                  (let [c (get @colors neighbor)]
                    (cond
                      (= :gray c) (record-cycle-if-present! cycles path neighbor)
                      (nil? c)    (dfs neighbor)
                      :else       nil)))
                (swap! path pop)
                (swap! colors assoc node :black)))]
      (doseq [start nodes]
        (dfs start)))
    @cycles))

;------------------------------------------------------------------------------ Layer 0
;; Bounded-retry helpers

(defn- exhaustion-edge?
  "True when edge carries an :on-budget-exhausted label."
  [edge]
  (= :on-budget-exhausted (:label edge)))

(defn- retry-self-loop?
  "True when `edge` is a :retry self-loop (from == to, label == :retry)."
  [edge]
  (and (= :retry (:label edge)) (= (:from edge) (:to edge))))

(defn- has-retry-edge?
  "True when the node has at least one :retry self-loop in its outgoing edges."
  [node edges-from]
  (some retry-self-loop? (get edges-from node [])))

(defn- budget-guarded?
  "True when the node has at least one outgoing edge with :budget-guarded? true
   in its :meta. This matches the graph.clj convention for phases that route
   through the verdict-driven guarded array (guarded phases with :on-fail)."
  [node edges-from]
  (some (fn [e] (true? (get-in e [:meta :budget-guarded?])))
        (get edges-from node [])))

(defn- any-exhaustion-reaches-terminal?
  "True when any exhaustion edge's :to target reaches a terminal node.
   Shared guard for check-retry-bounds and check-budget-paths."
  [adjacency terminal-nodes exhaustion-edges]
  (some (fn [ee] (bfs-reaches-terminal? adjacency terminal-nodes (:to ee)))
        exhaustion-edges))

;------------------------------------------------------------------------------ Layer 0
;; Per-check violation builders (private; called by Layer 1 check functions)

(defn- retry-edge-violations
  "Build zero, one, or two anomalies for a single :retry self-loop edge.
   Returns an empty vector when the retry is fully guarded (has :iterations
   AND an exhaustion edge reaching terminal)."
  [adjacency terminal-nodes edges-from retry-edge]
  (let [node             (:from retry-edge)
        has-iters?       (some? (get-in retry-edge [:meta :iterations]))
        exhaustion-edges (filter exhaustion-edge? (get edges-from node []))
        reaches?         (any-exhaustion-reaches-terminal? adjacency terminal-nodes exhaustion-edges)]
    (cond-> []
      (not has-iters?)
      (conj (anomaly/anomaly :invalid-input
                             (messages/ts :graph-validator/retry-missing-iterations)
                             {:node node :retry-edge retry-edge}))
      (not reaches?)
      (conj (anomaly/anomaly :invalid-input
                             (messages/ts :graph-validator/retry-missing-exhaustion-path)
                             {:node node})))))

(defn- budget-node-violations
  "Build zero, one, or two anomalies for a single budget-guarded phase node.
   Returns nil (treated as empty by mapcat callers) when the node is not
   budget-guarded."
  [adjacency terminal-nodes edges-from node]
  (when (budget-guarded? node edges-from)
    (let [exhaustion-edges (filter exhaustion-edge? (get edges-from node []))
          reaches?         (any-exhaustion-reaches-terminal? adjacency terminal-nodes exhaustion-edges)]
      (cond-> []
        (empty? exhaustion-edges)
        (conj (anomaly/anomaly :invalid-input
                               (messages/ts :graph-validator/missing-budget-exhausted-edge)
                               {:node node}))
        (and (seq exhaustion-edges) (not reaches?))
        (conj (anomaly/anomaly :invalid-input
                               (messages/ts :graph-validator/budget-exhausted-no-terminal)
                               {:node node}))))))

;------------------------------------------------------------------------------ Layer 1
;; Property 1 — Existence

(defn check-existence
  "Property 1: every :to target in :edges must exist in :nodes.
   Returns one anomaly per dangling edge. Callers may invoke this independently
   of the full validate-graph aggregate."
  [{:keys [nodes edges]}]
  (into []
        (comp
         (remove (fn [e] (contains? nodes (:to e))))
         (map (fn [e]
                (anomaly/anomaly :invalid-input
                                 (messages/ts :graph-validator/dangling-edge)
                                 {:edge e :missing-node (:to e)}))))
        edges))

;------------------------------------------------------------------------------ Layer 1
;; Property 2 — Termination

(defn check-termination
  "Property 2: from every non-terminal node, at least one terminal node must be
   reachable via BFS. Unreachable nodes are stall risks — workflows entering them
   can never complete. Returns one anomaly per node with no terminal path."
  [{:keys [nodes edges terminal-nodes]}]
  (let [adjacency    (build-adjacency edges)
        non-terminal (remove terminal-nodes nodes)]
    (into []
          (comp
           (remove (fn [node] (bfs-reaches-terminal? adjacency terminal-nodes node)))
           (map (fn [node]
                  (anomaly/anomaly :invalid-input
                                   (messages/ts :graph-validator/no-terminal-reachable)
                                   {:node node}))))
          non-terminal)))

;------------------------------------------------------------------------------ Layer 1
;; Property 3 — Cycles

(defn check-cycles
  "Property 3: detect all cycles via DFS back-edge coloring. Self-loops from
   :retry edges are expected and allowed — they are identified by the presence
   of a :retry self-loop on at least one node in the cycle. Cycles with NO
   such node are structural runaway risks (static analog of dogfood finding #988).
   Property 4 separately verifies that retry-based cycles have iteration bounds."
  [{:keys [nodes edges]}]
  (let [adjacency  (build-adjacency edges)
        edges-from (index-edges-by-from edges)
        cycles     (find-cycles adjacency nodes)]
    (into []
          (comp
           (remove (fn [cycle-nodes]
                     (some (fn [n] (has-retry-edge? n edges-from)) cycle-nodes)))
           (map (fn [cycle-nodes]
                  (anomaly/anomaly :invalid-input
                                   (messages/ts :graph-validator/runaway-cycle)
                                   {:cycle-nodes cycle-nodes}))))
          cycles)))

;------------------------------------------------------------------------------ Layer 1
;; Property 4 — Retry Bounds

(defn check-retry-bounds
  "Property 4: every :retry self-loop must have :iterations in its edge :meta
   (the iteration budget that makes the retry bounded) and the retry node must
   have an :on-budget-exhausted edge that reaches terminal directly or transitively.
   Missing either guard is the static analog of the implement-retry-runaway (#988)."
  [{:keys [edges terminal-nodes]}]
  (let [adjacency   (build-adjacency edges)
        edges-from  (index-edges-by-from edges)
        retry-edges (filter retry-self-loop? edges)]
    (vec (mapcat (partial retry-edge-violations adjacency terminal-nodes edges-from)
                 retry-edges))))

;------------------------------------------------------------------------------ Layer 1
;; Property 5 — Failure Paths

(defn check-failure-paths
  "Property 5: every non-terminal phase node must have at least one outgoing edge
   labeled :on-failure, :on-budget-exhausted, or :redirect that reaches a terminal
   node directly or transitively. Phases with no such path are silent-stall risks."
  [{:keys [phase-nodes edges terminal-nodes]}]
  (let [adjacency  (build-adjacency edges)
        edges-from (index-edges-by-from edges)]
    (into []
          (comp
           (remove terminal-nodes)
           (remove (fn [node]
                     (some (fn [e]
                             (and (contains? failure-edge-labels (:label e))
                                  (bfs-reaches-terminal? adjacency terminal-nodes (:to e))))
                           (get edges-from node []))))
           (map (fn [node]
                  (anomaly/anomaly :invalid-input
                                   (messages/ts :graph-validator/missing-failure-path)
                                   {:node node}))))
          phase-nodes)))

;------------------------------------------------------------------------------ Layer 1
;; Property 6 — Budget Paths

(defn check-budget-paths
  "Property 6: every budget-guarded phase node must have:
   (a) an :on-budget-exhausted edge, AND
   (b) that edge must reach a terminal directly or transitively.
   Budget-guarded nodes are identified by outgoing edges with :budget-guarded? true
   in :meta (set by graph.clj for guarded phases with :on-fail configuration).
   Budgeted phases without an exhaustion-to-terminal path are the stall pattern
   that the #988 runtime cap was built to prevent."
  [{:keys [phase-nodes edges terminal-nodes]}]
  (let [adjacency  (build-adjacency edges)
        edges-from (index-edges-by-from edges)]
    (vec (mapcat #(or (budget-node-violations adjacency terminal-nodes edges-from %) [])
                 phase-nodes))))

;------------------------------------------------------------------------------ Layer 1
;; Property 7 — Interceptors (opt-in)

(defn check-interceptors
  "Property 7 (opt-in, JVM-only): every phase keyword in :phase-nodes has a
   registered interceptor in phase/registry. Callers pass
   {:check-interceptors? true :interceptor-registered-fn (fn [kw] bool)} in opts.
   Returns [] when :check-interceptors? is false (default) or no fn is provided —
   which is the correct posture for babashka callers (interceptors register at JVM
   load via defmethod and are unavailable in babashka)."
  [{:keys [phase-nodes]} {:keys [check-interceptors? interceptor-registered-fn]}]
  (if (or (not check-interceptors?) (nil? interceptor-registered-fn))
    []
    (into []
          (comp
           (remove interceptor-registered-fn)
           (map (fn [phase-kw]
                  (anomaly/anomaly :invalid-input
                                   (messages/ts :graph-validator/unregistered-interceptor)
                                   {:phase phase-kw}))))
          phase-nodes)))

;------------------------------------------------------------------------------ Layer 2
;; Aggregation — public API

(defn validate-graph
  "Aggregate all structural property checks on a TransitionGraph.

   opts keys (all optional):
     :check-interceptors?     — boolean, default false; enables Property 7
                                (JVM-only; skip in babashka)
     :interceptor-registered-fn — (fn [phase-kw] → truthy/falsy); required
                                  when :check-interceptors? is true

   Returns {:valid? bool :errors [anomaly...] :warnings [anomaly...]}.
   All check findings land in :errors; :warnings is reserved for future
   severity splits."
  ([graph]
   (validate-graph graph {}))
  ([graph opts]
   (let [errors (vec (concat
                      (check-existence graph)
                      (check-termination graph)
                      (check-cycles graph)
                      (check-retry-bounds graph)
                      (check-failure-paths graph)
                      (check-budget-paths graph)
                      (check-interceptors graph opts)))]
     {:valid?   (empty? errors)
      :errors   errors
      :warnings []})))

(defn valid?
  "Convenience: returns true iff the TransitionGraph passes all structural checks."
  [graph]
  (:valid? (validate-graph graph)))

(defn validate-pipeline-graph
  "Convenience: build a TransitionGraph from `pipeline` and validate it in one call.

   Combines `phase-graph/build-transition-graph` + `validate-graph`. Useful at
   the workflow-config boundary, where the caller has a pipeline vector and wants
   a single structural verdict without an intermediate graph binding.

   `opts` — passed to both build-transition-graph (`:budget-guarded-phases`) and
            validate-graph (`:check-interceptors?`, `:interceptor-registered-fn`).

   Returns {:valid? bool :errors [anomaly...] :warnings [anomaly...]} when the
   pipeline is structurally valid (even if :valid? is false — the graph built fine
   but has property violations).

   Returns an anomaly map directly (satisfies `anomaly/anomaly?`) when the pipeline
   itself is malformed — empty, non-sequential, duplicate phases, or missing :phase
   keys. Callers must check `anomaly/anomaly?` before consuming graph-result keys."
  ([pipeline]
   (validate-pipeline-graph pipeline {}))
  ([pipeline opts]
   (let [graph-or-anomaly (phase-graph/build-transition-graph
                           pipeline
                           (select-keys opts [:budget-guarded-phases]))]
     (if (anomaly/anomaly? graph-or-anomaly)
       graph-or-anomaly
       (validate-graph graph-or-anomaly opts)))))

(comment
  ;; Quick REPL validation examples

  (def minimal-valid
    {:nodes          #{:plan :implement :failed :completed}
     :edges          [{:from :plan      :to :implement :label :next       :meta {}}
                      {:from :plan      :to :failed    :label :on-failure :meta {}}
                      {:from :implement :to :completed :label :next       :meta {}}
                      {:from :implement :to :failed    :label :on-failure :meta {}}]
     :terminal-nodes #{:failed :completed}
     :phase-nodes    #{:plan :implement}})

  (validate-graph minimal-valid)
  ;; => {:valid? true :errors [] :warnings []}

  (valid? minimal-valid)
  ;; => true

  ;; Property 1 — dangling :to target
  (check-existence
   {:nodes          #{:plan :failed}
    :edges          [{:from :plan :to :missing-phase :label :next :meta {}}
                     {:from :plan :to :failed :label :on-failure :meta {}}]
    :terminal-nodes #{:failed}
    :phase-nodes    #{:plan}})
  ;; => [anomaly {:missing-node :missing-phase}]

  ;; Property 3 — runaway cycle (no :retry node)
  (check-cycles
   {:nodes          #{:plan :implement :failed :completed}
    :edges          [{:from :plan      :to :implement :label :next       :meta {}}
                     {:from :implement :to :plan      :label :redirect   :meta {}}
                     {:from :plan      :to :failed    :label :on-failure :meta {}}
                     {:from :implement :to :failed    :label :on-failure :meta {}}]
    :terminal-nodes #{:failed :completed}
    :phase-nodes    #{:plan :implement}})
  ;; => [anomaly {:cycle-nodes #{:plan :implement}}]

  ;; Property 7 — injectable interceptor check
  (check-interceptors
   {:phase-nodes #{:plan :implement}}
   {:check-interceptors?       true
    :interceptor-registered-fn #{:plan}})
  ;; => [anomaly {:phase :implement}]
  )
