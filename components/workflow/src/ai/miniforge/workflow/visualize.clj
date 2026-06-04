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

(ns ai.miniforge.workflow.visualize
  "Mermaid stateDiagram-v2 export of a compiled execution machine.

   Phase 6 of the FSM RFC. Reads the RAW (pre-resolve) source-states
   map attached by `compile-execution-machine` so keyword-form guards
   and actions appear in edge labels — the post-Phase-3/4 verdict-
   driven shape is what a reviewer wants to audit.

   Output is plain text Mermaid that any Markdown renderer (GitHub,
   docs site, IDE preview) can show. Zero runtime cost: this is a
   one-shot pretty-printer invoked from the CLI."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Mermaid name sanitization
;;
;; Mermaid state ids can contain keywords like `[*]` (initial / final
;; pseudo-state) but not arbitrary punctuation. Our state-ids are
;; namespaced/clean keywords (e.g. `:phase-3-review`, `:paused-phase-
;; 0-plan`, `:completed`) — name them via `name` and they're already
;; valid Mermaid identifiers.

(defn- mermaid-id
  "Convert a clj-statecharts state id (keyword) into a Mermaid node id.
   Special-cases `:initial` / nil to the Mermaid `[*]` pseudo-state."
  [state-id]
  (cond
    (nil? state-id)        "[*]"
    (= :initial state-id)  "[*]"
    (keyword? state-id)    (name state-id)
    :else                  (str state-id)))

;------------------------------------------------------------------------------ Layer 1
;; Edge construction
;;
;; clj-statecharts transitions accept three shapes:
;;   - keyword               → bare target
;;   - map                   → {:target :guard :actions}
;;   - sequential of maps    → guarded array (first matching wins)

(defn- transition-entries
  [transition]
  (cond
    (keyword? transition)    [{:target transition}]
    (map? transition)        [transition]
    (sequential? transition) (filterv map? transition)
    :else                    []))

(defn- edge-label
  "Build the Mermaid edge label for a transition entry.
   Format:
     event-name
     event-name [guard-name]
     event-name {action,action}
     event-name [guard-name] {action,action}"
  [event-key {:keys [guard actions]}]
  (let [parts (cond-> [(str event-key)]
                guard         (conj (str "[" (str guard) "]"))
                (seq actions) (conj (str "{" (str/join "," (map str actions)) "}")))]
    (str/join " " parts)))

(defn- state-edges
  "Yield a seq of `[from-id to-id label]` edges for a single state.
   Skips entries with no target."
  [[state-id state-config]]
  (let [from-id (mermaid-id state-id)]
    (for [[event-key transition] (get state-config :on {})
          entry (transition-entries transition)
          :let [target (:target entry)]
          :when target]
      [from-id (mermaid-id target) (edge-label event-key entry)])))

(defn- final-states
  "Return the seq of state-ids marked as terminal."
  [source-states]
  (for [[state-id state-config] source-states
        :when (= :final (:type state-config))]
    state-id))

;------------------------------------------------------------------------------ Layer 2
;; Top-level printer

(defn machine->mermaid
  "Render a compiled execution machine as a Mermaid `stateDiagram-v2`
   block. Walks `:workflow/source-states` (the pre-resolve raw map)
   so keyword-form guards and actions appear in edge labels."
  [machine]
  (let [source-states (or (:workflow/source-states machine)
                          ;; Synthesize from `:states` when the raw
                          ;; map wasn't attached (older machines).
                          (:states machine))
        initial-state (or (:workflow/initial-state machine)
                          (:initial machine))
        edges (mapcat state-edges source-states)
        terminals (final-states source-states)]
    (str/join
     "\n"
     (concat
      ["stateDiagram-v2"]
      ;; Initial pseudo-state arrow
      (when initial-state
        [(str "  [*] --> " (mermaid-id initial-state))])
      ;; Edges
      (for [[from to label] edges]
        (str "  " from " --> " to " : " label))
      ;; Terminal arrows out
      (for [s (sort (map mermaid-id terminals))]
        (str "  " s " --> [*]"))))))

;------------------------------------------------------------------------------ Rich comment

(comment
  (require '[ai.miniforge.workflow.fsm :as fsm])
  (let [wf {:workflow/id :demo
            :workflow/pipeline [{:phase :plan}
                                {:phase :implement :on-fail :plan}
                                {:phase :verify    :on-fail :implement}
                                {:phase :review    :on-fail :implement}
                                {:phase :done}]}
        m  (fsm/compile-execution-machine wf)]
    (println (machine->mermaid m)))

  :leave-this-here)
