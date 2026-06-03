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

(ns ai.miniforge.workflow.guards
  "Named-guard registry for workflow execution machines.

   Workflows declare guarded transitions by KEYWORD reference, e.g.:

     {:on {:phase/fail
           [{:target :failed :guard :verdict/terminal?}
            {:target :implement
             :guard :budget/redirects-available?
             :actions [:redirect/inc-count]}
            {:target :failed}]}}

   At machine-compile time, every keyword-form `:guard` reference is
   resolved against the registry. A keyword that does not resolve causes
   `validate-execution-machine` to surface a `:dangling-guard-references`
   error — caught at workflow-validation time, NOT at the next dogfood
   run. This is the Phase 1 deliverable of the RFC at
   `docs/architecture/workflow-fsm-guarded-transitions.md`: the registry
   ships before any state uses guards, so when phase migrations begin,
   their references are validated from day one.

   Guard functions follow the clj-statecharts convention: `(state, event)
   → boolean`. Use `ai.miniforge.fsm.interface/guard` to lift a
   `(context, event) → boolean` predicate into that shape."
  (:require
   [clojure.string :as str]
   [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Registry

(defonce ^:private registry
  (atom {}))

(defn- ->class-name [v]
  (if (nil? v) "nil" (.getName (class v))))

(defn register-guard!
  "Register a guard predicate under `guard-key`. Subsequent compiles
   resolve `:guard guard-key` references against this entry.

   Validates inputs eagerly: a non-keyword key or non-fn value is a
   PROGRAMMER ERROR (typo, misread docs) discovered at namespace-load
   time, so per standards rule 005 `IllegalArgumentException` is the
   correct shape — not an anomaly return (the function is a side-effect
   registrar with no return-value channel where a caller would inspect
   one) and not a slingshot throw+ (no anomaly category to dispatch on)."
  [guard-key f]
  (when-not (keyword? guard-key)
    (throw (IllegalArgumentException.
             (messages/t :guards/non-keyword-key
                         {:value (pr-str guard-key)
                          :class (->class-name guard-key)}))))
  ;; ifn? not fn? — clj-statecharts accepts any IFn (clojure.lang.MultiFn,
  ;; keyword, set used as a predicate, etc), so the registry should too.
  (when-not (ifn? f)
    (throw (IllegalArgumentException.
             (messages/t :guards/non-fn-value
                         {:value     (pr-str f)
                          :class     (->class-name f)
                          :guard-key guard-key}))))
  (swap! registry assoc guard-key f))

(defn unregister-guard!
  "Remove a previously-registered guard. Primarily for test isolation —
   prefer namespace-level `register-guard!` calls in production."
  [guard-key]
  (swap! registry dissoc guard-key))

(defn lookup-guard
  "Return the registered function for `guard-key`, or nil."
  [guard-key]
  (get @registry guard-key))

(defn registered-keys
  "Return the set of all currently-registered guard keywords."
  []
  (set (keys @registry)))

(defn clear-registry!
  "Drop all registered guards. Test fixture only."
  []
  (reset! registry {}))

;------------------------------------------------------------------------------ Layer 1
;; Reference extraction
;;
;; Walks a compiled states map and surfaces every `:guard` reference so
;; the validator can check resolution coverage. The states map shape is:
;;
;;   {state-id {:on {event-key transition}}}
;;
;; where `transition` is one of:
;;   - keyword (target state-id; no guard)
;;   - map     ({:target ... :guard guard-ref :actions [...]})
;;   - vector  (ordered list of maps — first matching guard wins)
;;
;; This module only inspects the `:guard` slot. Action references will
;; be validated by an action registry in a later phase.

(defn- transition-guard-ref
  "Extract the `:guard` keyword from a single transition entry, or nil."
  [transition]
  (when (map? transition)
    (let [g (:guard transition)]
      (when (keyword? g) g))))

(defn- transition-entries
  "Normalize a transition value into a sequence of transition maps for
   walking. A keyword (bare target) has no guard slot; return empty."
  [transition]
  (cond
    (keyword? transition) []
    (map? transition)     [transition]
    (sequential? transition) (filter map? transition)
    :else []))

(defn- state-guard-refs
  [state-config]
  (->> (get state-config :on {})
       vals
       (mapcat transition-entries)
       (keep transition-guard-ref)))

(defn guard-references
  "Return a sorted vector of every `:guard` keyword that appears in the
   compiled states map. Order is deterministic for stable error messages."
  [compiled-states]
  (->> compiled-states
       vals
       (mapcat state-guard-refs)
       distinct
       sort
       vec))

(defn- state-guard-refs-with-location
  "Like `state-guard-refs`, but tagged with `{:state :event :guard}` so
   error messages can point the reader at the offending transition."
  [state-id state-config]
  (for [[event-key transition] (get state-config :on {})
        entry                  (transition-entries transition)
        :let [g (transition-guard-ref entry)]
        :when g]
    {:state state-id :event event-key :guard g}))

(defn guard-references-with-locations
  "Return every guard reference with its source `{:state :event}` tuple."
  [compiled-states]
  (->> compiled-states
       (mapcat (fn [[state-id config]] (state-guard-refs-with-location state-id config)))
       vec))

;------------------------------------------------------------------------------ Layer 2
;; Validation

(defn- dangling-reference-message
  [dangling-refs registered]
  (messages/t :guards/dangling-references
              {:dangling (str/join ", " (sort (distinct (map :guard dangling-refs))))
               :registered (if (seq registered)
                             (str/join ", " (sort registered))
                             "<none>")}))

(defn dangling-guard-references
  "Return the subset of `guard-references-with-locations` whose `:guard`
   keyword is not present in the current registry."
  [compiled-states]
  (let [known (registered-keys)]
    (->> (guard-references-with-locations compiled-states)
         (remove (fn [{:keys [guard]}] (contains? known guard)))
         vec)))

(defn validate-guard-references
  "Return `nil` when every `:guard` keyword in the compiled states resolves
   against the registry, or an error map of the same shape used by
   `validate-execution-machine` errors when one or more dangling
   references exist. Pure: does not mutate the registry."
  [compiled-states]
  (let [dangling (dangling-guard-references compiled-states)]
    (when (seq dangling)
      {:error :dangling-guard-references
       :references dangling
       :message (dangling-reference-message dangling (registered-keys))})))

;------------------------------------------------------------------------------ Rich comment

(comment
  ;; Register a guard.
  (register-guard! :budget/redirects-available?
                   (fn [state _event]
                     (< (:redirect-count (:context state) 0) 5)))

  ;; Validate a compiled states map (Phase 1: no states use guards yet,
  ;; so the validator returns nil for everything).
  (validate-guard-references {:phase-0-review
                              {:on {:phase/fail
                                    [{:target :failed :guard :verdict/terminal?}
                                     {:target :phase-1-implement}]}}})

  :leave-this-here)
