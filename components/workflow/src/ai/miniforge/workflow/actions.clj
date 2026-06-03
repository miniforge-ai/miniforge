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

(ns ai.miniforge.workflow.actions
  "Named-action registry for workflow execution machines.

   Companion to `ai.miniforge.workflow.guards`. Workflows declare typed
   transition side effects (FSM-owned accounting bumps, hook calls) by
   KEYWORD reference, e.g.:

     {:target on-fail-state
      :guard  :config/on-fail-set?
      :actions [:redirect/inc-count]}

   At machine-compile time, every keyword-form action reference is
   resolved against this registry. Dangling references surface as
   `:dangling-action-references` errors through the same path as the
   guard-dangling check — caught at workflow-validation time, not at the
   next dogfood run.

   This is the Phase 2 deliverable that lets the FSM own its accounting
   side effects (e.g. `:redirect/inc-count`) instead of having the runner
   wrap the FSM call with a separate budget check — the dogfood-class
   bug class becomes structurally impossible to express once Phase 2b
   wires this into the review phase's guarded `:phase/fail` array.

   Action functions follow the clj-statecharts convention: a bare action
   fn signature is `(state, event) → side-effect`. Use
   `ai.miniforge.fsm.interface/assign` to wrap a `(context, event) →
   context'` updater as an FSM-context mutation."
  (:require
   [clojure.string :as str]
   [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Registry

(defonce ^:private registry
  (atom {}))

(defn- ->class-name [v]
  (if (nil? v) "nil" (.getName (class v))))

(defn register-action!
  "Register an action implementation under `action-key`. Subsequent
   compiles substitute `:actions [action-key]` references with the
   registered value.

   Validates inputs eagerly: a non-keyword key or non-IFn value is a
   PROGRAMMER ERROR (typo, misread docs) discovered at namespace-load
   time, so per standards rule 005 `IllegalArgumentException` is the
   correct shape — not an anomaly return (the function is a side-effect
   registrar with no return-value channel where a caller would inspect
   one) and not a slingshot throw+ (no anomaly category to dispatch on)."
  [action-key f]
  (when-not (keyword? action-key)
    (throw (IllegalArgumentException.
             (messages/t :actions/non-keyword-key
                         {:value (pr-str action-key)
                          :class (->class-name action-key)}))))
  ;; ifn? not fn? — clj-statecharts accepts any IFn (multimethods,
  ;; keywords-used-as-predicates, sets), so the registry should match.
  (when-not (ifn? f)
    (throw (IllegalArgumentException.
             (messages/t :actions/non-fn-value
                         {:value      (pr-str f)
                          :class      (->class-name f)
                          :action-key action-key}))))
  (swap! registry assoc action-key f))

(defn unregister-action!
  "Remove a previously-registered action. Primarily for test isolation —
   prefer namespace-level `register-action!` calls in production."
  [action-key]
  (swap! registry dissoc action-key))

(defn lookup-action
  "Return the registered function for `action-key`, or nil."
  [action-key]
  (get @registry action-key))

(defn registered-keys
  "Return the set of all currently-registered action keywords."
  []
  (set (keys @registry)))

(defn clear-registry!
  "Drop all registered actions. Test fixture only."
  []
  (reset! registry {}))

;------------------------------------------------------------------------------ Layer 1
;; Reference extraction
;;
;; Walks a compiled states map and surfaces every `:actions` keyword
;; reference so the validator can check resolution coverage. Mirrors
;; `guards/guard-references` — same transition shapes, same dedup
;; semantics.

(defn- action-refs-in-transition
  "Extract action keywords from a single transition entry, or empty."
  [transition]
  (when (map? transition)
    (->> (get transition :actions [])
         (filter keyword?))))

(defn- transition-entries
  "Normalize a transition value into a sequence of transition maps for
   walking. A keyword (bare target) has no actions; return empty."
  [transition]
  (cond
    (keyword? transition)    []
    (map? transition)        [transition]
    (sequential? transition) (filter map? transition)
    :else                    []))

(defn- state-action-refs
  [state-config]
  (->> (get state-config :on {})
       vals
       (mapcat transition-entries)
       (mapcat action-refs-in-transition)))

(defn action-references
  "Return a sorted vector of every `:actions` keyword that appears in the
   compiled states map. Order is deterministic for stable error messages."
  [compiled-states]
  (->> compiled-states
       vals
       (mapcat state-action-refs)
       distinct
       sort
       vec))

(defn- state-action-refs-with-location
  "Like `state-action-refs`, but tagged with `{:state :event :action}` so
   error messages can point the reader at the offending transition."
  [state-id state-config]
  (for [[event-key transition] (get state-config :on {})
        entry                  (transition-entries transition)
        action                 (action-refs-in-transition entry)]
    {:state state-id :event event-key :action action}))

(defn action-references-with-locations
  "Return every action reference with its source `{:state :event}` tuple."
  [compiled-states]
  (->> compiled-states
       (mapcat (fn [[state-id config]] (state-action-refs-with-location state-id config)))
       vec))

;------------------------------------------------------------------------------ Layer 2
;; Validation

(defn- dangling-reference-message
  [dangling-refs registered]
  (messages/t :actions/dangling-references
              {:dangling (str/join ", " (sort (distinct (map :action dangling-refs))))
               :registered (if (seq registered)
                             (str/join ", " (sort registered))
                             "<none>")}))

(defn dangling-action-references
  "Return the subset of `action-references-with-locations` whose
   `:action` keyword is not present in the current registry."
  [compiled-states]
  (let [known (registered-keys)]
    (->> (action-references-with-locations compiled-states)
         (remove (fn [{:keys [action]}] (contains? known action)))
         vec)))

(defn validate-action-references
  "Return `nil` when every `:actions` keyword in the compiled states
   resolves against the registry, or an error map of the same shape used
   by `validate-execution-machine` errors when one or more dangling
   references exist. Pure: does not mutate the registry."
  [compiled-states]
  (let [dangling (dangling-action-references compiled-states)]
    (when (seq dangling)
      {:error :dangling-action-references
       :references dangling
       :message (dangling-reference-message dangling (registered-keys))})))

;------------------------------------------------------------------------------ Layer 3
;; Resolution
;;
;; Substitutes every keyword-form `:actions` reference with the actual
;; registered fn. Called from `compile-execution-machine` AFTER
;; validation so dangling refs are surfaced first.
;;
;; A keyword that survived validation but is not registered surfaces as
;; an IllegalStateException at resolve time — programmer-error guard,
;; not an anomaly return, because the validator should have caught it.

(defn- resolve-action-keyword
  [k]
  (or (lookup-action k)
      (throw (IllegalStateException.
               (messages/t :actions/unregistered-at-resolve
                           {:action k})))))

(defn- resolve-actions-in-entry
  [entry]
  (if-let [acts (seq (get entry :actions))]
    (assoc entry :actions (mapv (fn [a] (if (keyword? a) (resolve-action-keyword a) a))
                                 acts))
    entry))

(defn- resolve-actions-in-transition
  [transition]
  (cond
    (keyword? transition)    transition
    (map? transition)        (resolve-actions-in-entry transition)
    (sequential? transition) (mapv (fn [t] (if (map? t) (resolve-actions-in-entry t) t))
                                    transition)
    :else                    transition))

(defn resolve-action-keywords
  "Walk `compiled-states` and substitute every keyword-form `:actions`
   reference with its registered fn. Idempotent — entries with no
   keyword refs pass through. Validator should have run first to ensure
   coverage."
  [compiled-states]
  (into {}
        (map (fn [[state-id state-config]]
               (let [on (get state-config :on {})
                     resolved-on (into {}
                                       (map (fn [[event-key t]]
                                              [event-key (resolve-actions-in-transition t)]))
                                       on)]
                 [state-id (assoc state-config :on resolved-on)])))
        compiled-states))

;------------------------------------------------------------------------------ Rich comment

(comment
  ;; Register an action.
  (register-action! :redirect/inc-count
                    (fn [state _event]
                      (update-in state [:context :redirect-count] (fnil inc 0))))

  ;; Validate references. Returns nil when clean.
  (validate-action-references
    {:phase-0-review {:on {:phase/fail [{:target :failed
                                         :actions [:redirect/inc-count]}]}}})

  ;; Substitute keyword refs for the FSM compiler.
  (resolve-action-keywords
    {:phase-0-review {:on {:phase/fail [{:target :failed
                                         :actions [:redirect/inc-count]}]}}})

  :leave-this-here)
