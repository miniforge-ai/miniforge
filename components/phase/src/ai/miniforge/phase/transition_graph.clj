(ns ai.miniforge.phase.transition-graph
  "Graph data model for phase-transition validation.

   Defines the edge-label vocabulary, terminal/guarded-phase constants,
   Malli schemas, and factory functions used by the transition-graph
   validator and any consumers that need to construct or inspect graphs.

   Design constraints:
   - Pure data only.  No protocols, no defrecord, no Java interop beyond
     bb builtins.  File is bb-loadable.
   - No exceptions thrown.  All error paths return anomaly maps (rule 005).
   - Schemas are raw Malli vectors; validate at the boundary (rule 004)."
  (:require [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Edge-label constants
;; Each def names one edge label keyword and documents when that transition fires.

(def edge-next
  "Default forward-progress transition.
   Fires when a phase completes with no special verdict — execution moves
   to the next phase in the linear sequence."
  :edge/next)

(def edge-on-failure
  "Failure-routing transition.
   Fires when a phase exits with a non-terminal error verdict that the
   framework should handle (redirect, backoff, escalation)."
  :edge/on-failure)

(def edge-on-success
  "Explicit success branch.
   Used when a phase has more than one forward exit and the happy path
   must be distinguished from the default `:edge/next` (e.g. a gate
   phase that routes differently based on approval vs. pass-through)."
  :edge/on-success)

(def edge-retry
  "Retry-loop transition.
   Fires when a guarded phase receives a retriable verdict and the
   consecutive-retry cap has not been reached.  Loops back to the same
   phase node."
  :edge/retry)

(def edge-already-done
  "Already-implemented short-circuit transition.
   Fires when a phase (typically :implement) determines the work is
   already complete.  Skips remaining phases and routes directly toward
   a terminal success state."
  :edge/already-done)

(def edge-terminal-verdict
  "Terminal-verdict fast-exit transition.
   Fires when a guarded phase emits a verdict that is unconditionally
   terminal (e.g. :implement/empty-diff, :review/stagnated).  Bypasses
   the normal on-failure redirect loop and routes to a failed terminal."
  :edge/terminal-verdict)

(def edge-budget-exhausted
  "Budget-exhaustion transition.
   Fires when the workflow detects that token or cost budget is
   exhausted before a terminal state is reached.  Routes to the
   :budget-exhausted terminal node, which the validator must verify
   is itself a terminal state."
  :edge/budget-exhausted)

;; ── Derived vocabulary set ──────────────────────────────────────────────────

(def all-edge-labels
  "Complete set of valid edge labels.  Derived from the individual
   constants; use for membership checks and schema generation rather
   than hard-coding the set in multiple places."
  #{edge-next
    edge-on-failure
    edge-on-success
    edge-retry
    edge-already-done
    edge-terminal-verdict
    edge-budget-exhausted})

;; ── Phase-category constants ─────────────────────────────────────────────────

(def terminal-states
  "Workflow states that represent the end of execution.
   A graph is only valid if every reachable path eventually arrives at
   one of these states.  Mirrors the convention in workflow/fsm.clj."
  #{:completed :failed :cancelled})

(def guarded-phases
  "Phases whose :phase/fail dispatch routes through the verdict-driven
   guarded array rather than a simple on-failure edge.
   Kept in sync with workflow.fsm/guarded-phases (Phase 3c set)."
  #{:review :verify :release :implement})

;; ── Malli schemas ────────────────────────────────────────────────────────────

(def EdgeLabel
  "Malli schema for valid edge-label keywords.
   Apply at graph-construction boundaries to catch typos early."
  (into [:enum] all-edge-labels))

(def NodeSchema
  "Malli schema for a single graph node.

   - :node/id       — unique keyword identifier for this node in the graph
   - :node/phase    — the workflow phase keyword this node represents
   - :node/index    — 0-based position in the canonical phase sequence
   - :node/terminal? — true when this node is a terminal state; the graph
                       validator will verify terminal? nodes are in
                       `terminal-states`"
  [:map
   [:node/id :keyword]
   [:node/phase :keyword]
   [:node/index :int]
   [:node/terminal? :boolean]])

(def EdgeSchema
  "Malli schema for a directed graph edge.

   - :edge/from  — source node id
   - :edge/to    — target node id
   - :edge/label — one of the `edge-*` constants; governs when this
                   transition fires
   - :edge/guard — optional keyword naming the FSM guard predicate that
                   must return truthy for this edge to be traversed"
  [:map
   [:edge/from :keyword]
   [:edge/to :keyword]
   [:edge/label EdgeLabel]
   [:edge/guard {:optional true} [:maybe :keyword]]])

(def GraphSchema
  "Malli schema for the complete transition graph.

   - :graph/nodes — ordered vector of NodeSchema maps
   - :graph/edges — vector of EdgeSchema maps (may reference any node id)
   - :graph/entry — node id of the graph's entry point; must appear in
                    :graph/nodes"
  [:map
   [:graph/nodes [:vector NodeSchema]]
   [:graph/edges [:vector EdgeSchema]]
   [:graph/entry :keyword]])

;------------------------------------------------------------------------------ Layer 1
;; Factory functions — construct validated-shape maps; no hand-built literals
;; at call sites (rule 003).

(defn node
  "Create a graph node map.

   Parameters:
   - `id`        — unique keyword identifier (e.g. `:plan-node`)
   - `phase`     — workflow phase keyword (e.g. `:plan`)
   - `index`     — 0-based position in the canonical phase sequence
   - `terminal?` — true when this node represents a terminal workflow state"
  [id phase index terminal?]
  {:node/id       id
   :node/phase    phase
   :node/index    index
   :node/terminal? terminal?})

(defn terminal-node
  "Convenience factory for terminal nodes.
   Sets `:node/terminal?` to `true` and uses the phase keyword as the id
   when no explicit id is supplied."
  ([phase index]
   (node phase phase index true))
  ([id phase index]
   (node id phase index true)))

(defn edge
  "Create a directed graph edge map.

   3-arity: `(edge from to label)` — unconditional transition.
   4-arity: `(edge from to label guard)` — guarded transition; `guard` is
            the keyword naming the FSM guard predicate."
  ([from to label]
   {:edge/from  from
    :edge/to    to
    :edge/label label})
  ([from to label guard]
   {:edge/from  from
    :edge/to    to
    :edge/label label
    :edge/guard guard}))

(defn graph
  "Create the graph envelope map.

   Parameters:
   - `entry` — node id of the entry-point node
   - `nodes` — sequence of node maps (NodeSchema)
   - `edges` — sequence of edge maps (EdgeSchema)"
  [entry nodes edges]
  {:graph/entry entry
   :graph/nodes (vec nodes)
   :graph/edges (vec edges)})

(comment
  ;; REPL examples — build a tiny two-node graph
  (def sample-graph
    (graph :plan-node
           [(node :plan-node :plan 0 false)
            (terminal-node :completed 1)]
           [(edge :plan-node :completed edge-next)]))

  (m/validate GraphSchema sample-graph)
  ;; => true

  (m/validate EdgeLabel edge-budget-exhausted)
  ;; => true

  (m/validate NodeSchema (terminal-node :failed 1))
  ;; => true
  )
