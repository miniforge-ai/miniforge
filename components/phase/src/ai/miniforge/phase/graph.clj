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

(ns ai.miniforge.phase.graph
  "Graph data structure and derivation for workflow phase transition graphs.

   A TransitionGraph is a plain map (not a Malli schema — spec non-goal).
   It captures the structural shape of the FSM that workflow/fsm.clj would
   compile, expressed as a data value that is inspectable, serialisable,
   and testable without running the FSM runtime.

   Structural keys:
     :nodes          — set of keyword node identifiers (phases + terminals)
     :edges          — vector of {:from kw :to kw :label kw :meta map}
     :terminal-nodes — subset of :nodes that are terminal (no outgoing progression)
     :phase-nodes    — subset of :nodes that are actual workflow phases

   Edge label vocabulary (see named constants below):
     :next :on-failure :on-success :on-budget-exhausted :retry
     :redirect :already-done :pause :cancel

   Semantic alignment:
   The derivation faithfully models `workflow/fsm.clj`'s `build-phase-state`
   and `guarded-fail-transition`. Each FSM branch becomes a labeled edge:
     * terminal-verdict branch  → :failed   (labeled :on-failure)
     * budget-spent branch      → :failed   (labeled :on-budget-exhausted)
     * on-fail redirect branch  → on-fail target (labeled :redirect)
     * default branch           → :failed   (labeled :on-failure)
     * :phase/succeed           → next/on-success (labeled :on-success / :next)
     * :phase/already-done      → :done or :completed (labeled :already-done)
     * :phase/retry             → self-loop (labeled :retry)
     * :pause                   → self (labeled :pause)
     * :cancel                  → :cancelled (labeled :cancel)

   Anomaly returns: on malformed input, returns an anomaly map satisfying
   `ai.miniforge.anomaly.interface/anomaly?`. Callers must check before
   consuming graph keys."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Named constants — edge labels

(def label-next
  "Edge label: linear pipeline progression from phase N to phase N+1."
  :next)

(def label-on-failure
  "Edge label: phase failed and transitions to the failure terminal or on-fail target."
  :on-failure)

(def label-on-success
  "Edge label: phase succeeded and transitions to an explicit on-success target
   (distinct from linear :next when the pipeline has a non-sequential success path)."
  :on-success)

(def label-on-budget-exhausted
  "Edge label: phase redirect budget is spent — force-transitions to :failed.
   Models the `budget/redirects-spent?` guard branch in guarded-fail-transition."
  :on-budget-exhausted)

(def label-retry
  "Edge label: self-loop when phase is retried within its iteration budget."
  :retry)

(def label-redirect
  "Edge label: on-fail redirect branch — transitions to the configured :on-fail
   target. Present only when :on-fail is configured AND the phase is in the
   guarded set. Meta carries :budget-guarded? true to surface the
   budget-precedes-redirect invariant."
  :redirect)

(def label-already-done
  "Edge label: phase result is :already-done — transitions to :done or :completed."
  :already-done)

(def label-pause
  "Edge label: phase execution is paused (supervisor-initiated)."
  :pause)

(def label-cancel
  "Edge label: workflow is cancelled."
  :cancel)

;------------------------------------------------------------------------------ Layer 0
;; Named constants — terminal nodes

(def canonical-terminal-nodes
  "Canonical set of terminal node identifiers. Callers may extend this set
   when constructing a graph (e.g. add :done when a :done phase is present
   in the pipeline). Terminal nodes have no outgoing progression edges."
  #{:completed :failed :cancelled})

(def default-budget-guarded-phases
  "Fallback set of phases that route :phase/fail through the verdict-driven
   guarded array. Matches `workflow/fsm.clj`'s `guarded-phases` set.

   The active operational value lives in
   `resources/config/phase/graph.edn` under `:phase/budget-guarded-phases`.
   Callers may pass a different set via the `opts` argument to
   `build-transition-graph` — this constant is the code-side fallback when
   no config override is present."
  #{:review :verify :release :implement})

(def budgeted-phase-iterations-threshold
  "Minimum iteration-budget value for a phase to be considered 'budgeted'.
   A phase with :budget :iterations > this value gets a :retry self-loop edge."
  1)

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers — pipeline indexing

(defn- first-phase-index
  "Return the index of the first pipeline entry whose :phase matches `phase-kw`,
   or nil when not found."
  [pipeline phase-kw]
  (some (fn [[idx cfg]]
          (when (= phase-kw (:phase cfg)) idx))
        (map-indexed vector pipeline)))

(defn- phase-node-id
  "Derive the graph node identifier for a pipeline entry.
   Mirrors `workflow/fsm.clj`'s `phase-state-id` naming but returns the
   raw phase keyword — the graph works at the phase level, not the FSM
   state-id level."
  [config]
  (:phase config))

(defn- resolve-target-node
  "Return the graph node id for a pipeline index, falling back to `fallback`."
  [pipeline idx fallback]
  (if idx
    (phase-node-id (get pipeline idx))
    fallback))

(defn- done-node
  "Return the :done node id when :done appears in the pipeline, else nil."
  [pipeline]
  (when (first-phase-index pipeline :done) :done))

(defn- already-done-target
  "Return the :already-done transition target: :done when the pipeline
   contains a :done phase, otherwise :completed."
  [pipeline]
  (if-let [dn (done-node pipeline)]
    dn
    :completed))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers — edge constructors

(defn- edge
  "Construct a graph edge map."
  ([from to label]
   (edge from to label {}))
  ([from to label meta-map]
   {:from  from
    :to    to
    :label label
    :meta  meta-map}))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers — validation

(defn- empty-pipeline-anomaly
  []
  (anomaly/anomaly :invalid-input
                   (messages/ts :anomaly/empty-pipeline)
                   {}))

(defn- malformed-entry-anomaly
  [idx entry]
  (anomaly/anomaly :invalid-input
                   (messages/ts :anomaly/malformed-pipeline-entry)
                   {:index idx :entry entry}))

(defn- duplicate-phases-anomaly
  [dupes]
  (anomaly/anomaly :invalid-input
                   (messages/ts :anomaly/duplicate-phases)
                   {:duplicate-phases (vec dupes)}))

(defn- per-entry-error
  "Return the first per-entry structural error in `pipeline`, or nil."
  [pipeline]
  (some (fn [[idx entry]]
          (when-not (and (map? entry) (keyword? (:phase entry)))
            (malformed-entry-anomaly idx entry)))
        (map-indexed vector pipeline)))

(defn- duplicate-phase-error
  "Return an anomaly when any :phase keyword appears more than once, or nil."
  [pipeline]
  (let [freqs (frequencies (map :phase pipeline))
        dupes (keep (fn [[k n]] (when (> n 1) k)) freqs)]
    (when (seq dupes)
      (duplicate-phases-anomaly dupes))))

(defn- validate-pipeline
  "Return nil when the pipeline is structurally valid, or an anomaly map.
   Guards are applied in order: nil → non-sequential → empty → per-entry →
   duplicate-phase. The nil and sequential? checks deliberately precede empty?
   to avoid calling seq on non-Seqable values (which would throw
   ClassCastException). Duplicate :phase keywords are rejected because
   graph-node identifiers must be unique — a pipeline with duplicates
   produces ambiguous edge routing and deduplicated node sets."
  [pipeline]
  (cond
    (nil? pipeline)
    (empty-pipeline-anomaly)

    (not (sequential? pipeline))
    (malformed-entry-anomaly -1 pipeline)

    (empty? pipeline)
    (empty-pipeline-anomaly)

    :else
    (or (per-entry-error pipeline)
        (duplicate-phase-error pipeline))))

;------------------------------------------------------------------------------ Layer 1
;; Edge derivation — one function per edge class

(defn- next-edges
  "Derive :next edges — linear progression from phase N to phase N+1.
   Terminal phases have no :next edge."
  [pipeline]
  (let [n (count pipeline)]
    (keep-indexed (fn [idx cfg]
                    (let [terminal?  (get cfg :terminal? false)
                          next-idx   (inc idx)]
                      (when (and (not terminal?) (< next-idx n))
                        (edge (phase-node-id cfg)
                              (phase-node-id (get pipeline next-idx))
                              label-next))))
                  pipeline)))

(defn- success-edges
  "Derive :on-success edges for phases with explicit :on-success config.
   Falls back to the :next edge when :on-success is absent (modeled in
   next-edges already, so we emit only when the target DIFFERS from N+1).

   Constraint: :on-success values must be phase keywords present in the
   pipeline. Terminal-node targets (:failed, :completed, :cancelled) are not
   pipeline phases and will not match any pipeline entry — resolve-target-node
   falls back to :completed for unresolved targets, producing a graph edge
   that silently differs from config intent. Validate the pipeline's
   :on-success values before calling this function if you need strict
   target-resolution guarantees."
  [pipeline]
  (keep-indexed
   (fn [idx cfg]
     (let [on-success-kw (:on-success cfg)
           next-idx      (inc idx)
           next-node     (when (< next-idx (count pipeline))
                           (phase-node-id (get pipeline next-idx)))
           target-idx    (when on-success-kw
                           (first-phase-index pipeline on-success-kw))
           target-node   (resolve-target-node pipeline target-idx :completed)]
       ;; Only emit when explicit on-success differs from the default next
       (when (and on-success-kw (not= target-node next-node))
         (edge (phase-node-id cfg) target-node label-on-success))))
   pipeline))

(defn- failure-edges
  "Derive :on-failure edges to :failed for phases without :on-fail config,
   and for the terminal/default branch of the guarded array."
  [pipeline]
  (map (fn [cfg]
         (edge (phase-node-id cfg) :failed label-on-failure))
       pipeline))

(defn- retry-edges
  "Derive :retry self-loop edges for phases whose iteration budget > 1."
  [pipeline]
  (keep (fn [cfg]
          (let [iterations (get-in cfg [:budget :iterations] 1)]
            (when (> iterations budgeted-phase-iterations-threshold)
              (edge (phase-node-id cfg) (phase-node-id cfg) label-retry))))
        pipeline))

(defn- budget-exhausted-edges
  "Derive :on-budget-exhausted edges for guarded phases with :on-fail.
   Models the `budget/redirects-spent?` guard branch in guarded-fail-transition:
   when the redirect budget is spent the phase transitions directly to :failed."
  [pipeline guarded-phases]
  (keep (fn [cfg]
          (let [phase-kw   (:phase cfg)
                on-fail-kw (:on-fail cfg)]
            ;; Only phases that are guarded AND have :on-fail have a meaningful
            ;; budget-exhausted branch that differs from the plain failure edge.
            (when (and (contains? guarded-phases phase-kw) on-fail-kw)
              (edge phase-kw :failed label-on-budget-exhausted
                    {:budget-guarded? true}))))
        pipeline))

(defn- already-done-edges
  "Derive :already-done edges — all active phases can short-circuit to :done
   or :completed when their result is :already-done."
  [pipeline]
  (let [target (already-done-target pipeline)]
    (map (fn [cfg]
           (edge (phase-node-id cfg) target label-already-done))
         pipeline)))

(defn- redirect-edges
  "Derive :redirect edges for guarded phases with :on-fail configured.
   Models the third branch of guarded-fail-transition (the actual redirect).
   Meta carries :budget-guarded? true to surface the budget-precedes-redirect
   invariant: the :on-budget-exhausted edge fires BEFORE this one."
  [pipeline guarded-phases]
  (keep (fn [cfg]
          (let [phase-kw   (:phase cfg)
                on-fail-kw (:on-fail cfg)]
            (when (and (contains? guarded-phases phase-kw) on-fail-kw)
              (let [fail-idx    (first-phase-index pipeline on-fail-kw)
                    fail-target (resolve-target-node pipeline fail-idx :failed)]
                (edge phase-kw fail-target label-redirect
                      {:budget-guarded? true})))))
        pipeline))

(defn- pause-edges
  "Derive :pause self-edges for all active phases."
  [pipeline]
  (map (fn [cfg]
         (edge (phase-node-id cfg) (phase-node-id cfg) label-pause))
       pipeline))

(defn- cancel-edges
  "Derive :cancel edges — all active phases can be cancelled."
  [pipeline]
  (map (fn [cfg]
         (edge (phase-node-id cfg) :cancelled label-cancel))
       pipeline))

(defn- derive-all-edges
  "Collect and concatenate all labeled edges for the pipeline.
   Called once by build-transition-graph after pipeline validation."
  [pipeline guarded-phases]
  (vec (concat
        (next-edges pipeline)
        (success-edges pipeline)
        (failure-edges pipeline)
        (retry-edges pipeline)
        (budget-exhausted-edges pipeline guarded-phases)
        (already-done-edges pipeline)
        (redirect-edges pipeline guarded-phases)
        (pause-edges pipeline)
        (cancel-edges pipeline))))

;------------------------------------------------------------------------------ Layer 1
;; Node derivation

(defn- phase-nodes
  "Collect all phase node ids from the pipeline."
  [pipeline]
  (into #{} (map phase-node-id) pipeline))

(defn- terminal-nodes-for
  "Derive the terminal node set for this pipeline.
   Extends the canonical set with :done when the pipeline contains it."
  [pipeline]
  (let [has-done? (some? (first-phase-index pipeline :done))]
    (cond-> canonical-terminal-nodes
      has-done? (conj :done))))

(defn- all-nodes
  "Union of phase nodes + terminal nodes."
  [phase-set terminal-set]
  (into phase-set terminal-set))

;------------------------------------------------------------------------------ Layer 2
;; Public derivation function

(defn build-transition-graph
  "Derive a TransitionGraph from a workflow execution pipeline.

   `pipeline` — vector of phase config maps, each with keys:
     :phase      (required, keyword) — phase identifier; must be unique
                  across the pipeline (duplicate :phase keywords are rejected
                  as malformed — graph-node identifiers must be unique)
     :on-fail    (optional, keyword) — redirect target on failure
     :on-success (optional, keyword) — explicit success target; must be a
                  phase keyword present in the pipeline (not a terminal node)
     :budget     (optional, map)    — {:iterations n}
     :terminal?  (optional, bool)   — true for terminal phases

   `opts` — optional config overrides (map):
     :budget-guarded-phases — set of phase keywords that route :phase/fail
                              through the verdict-driven guarded array;
                              defaults to `default-budget-guarded-phases`.
                              The operational value lives in
                              `resources/config/phase/graph.edn` under
                              `:phase/budget-guarded-phases`.

   Returns either a TransitionGraph map or an anomaly map satisfying
   `ai.miniforge.anomaly.interface/anomaly?`. Callers must check the
   return value before consuming graph keys.

   TransitionGraph keys:
     :nodes          — set of all node identifiers
     :edges          — vector of {:from :to :label :meta} maps
     :terminal-nodes — subset of :nodes that are terminal
     :phase-nodes    — subset of :nodes representing actual phases"
  ([pipeline]
   (build-transition-graph pipeline {}))
  ([pipeline opts]
   (let [guarded-phases   (get opts :budget-guarded-phases default-budget-guarded-phases)
         validation-error (validate-pipeline pipeline)]
     (if validation-error
       validation-error
       (let [phase-set    (phase-nodes pipeline)
             terminal-set (terminal-nodes-for pipeline)
             node-set     (all-nodes phase-set terminal-set)
             edges        (derive-all-edges pipeline guarded-phases)]
         {:nodes          node-set
          :edges          edges
          :terminal-nodes terminal-set
          :phase-nodes    phase-set})))))

(comment
  ;; Basic linear pipeline
  (build-transition-graph
   [{:phase :plan}
    {:phase :implement :budget {:iterations 3}}
    {:phase :verify}
    {:phase :done :terminal? true}])

  ;; Pipeline with on-fail redirect (guarded phase)
  (build-transition-graph
   [{:phase :plan}
    {:phase :implement :budget {:iterations 3} :on-fail :plan}
    {:phase :verify    :on-fail :implement}
    {:phase :review    :on-fail :implement}
    {:phase :done :terminal? true}])

  ;; Custom guarded set via opts (e.g. loaded from graph.edn)
  (build-transition-graph
   [{:phase :plan}
    {:phase :implement :on-fail :plan}]
   {:budget-guarded-phases #{:plan :implement}})

  ;; Duplicate :phase keyword — returns anomaly
  (build-transition-graph [{:phase :plan} {:phase :plan}])

  ;; Malformed — no :phase key
  (build-transition-graph [{:name :broken}])

  ;; Empty pipeline — returns anomaly
  (build-transition-graph [])

  :leave-this-here)
