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

(ns ai.miniforge.gate.registry
  "Gate registry using multimethods.

   Gates register via defmethod, providing:
   - :check  (fn [artifact ctx] -> {:passed? bool :errors [...]})
   - :repair (fn [artifact errors ctx] -> {:success? bool :artifact ...})

   Pattern borrowed from Ixi's interceptor registry.")

;------------------------------------------------------------------------------ Layer 0
;; Registry tracking

(defonce registered-gates (atom #{}))

(defn register-gate!
  "Track a gate as registered."
  [gate-kw]
  (swap! registered-gates conj gate-kw))

;------------------------------------------------------------------------------ Layer 1
;; Multimethod registry

(defmulti get-gate
  "Get gate implementation for a keyword.

   Dispatches on gate keyword.

   Returns:
     {:name keyword?
      :check (fn [artifact ctx] -> {:passed? bool :errors [...]})
      :repair (fn [artifact errors ctx] -> {:success? bool :artifact ...})}"
  identity)

;; Unknown gates fail CLOSED. A phase that references a gate the registry cannot
;; resolve — misspelled, renamed, or never registered — must halt, not wave the
;; artifact through. In a system that sells fail-closed governance the unknown
;; case is the dangerous one. Deliberate pass-through has an explicit, separate
;; home: the :noop gate below, so "unknown" and "intentionally skipped" stay
;; distinct and greppable.
(defmethod get-gate :default
  [gate-kw]
  {:name gate-kw
   :description "Unknown gate (fail-closed)"
   :check (fn [_artifact _ctx]
            {:passed? false
             :errors [{:type :unknown-gate
                       :message (str "Unknown gate: " gate-kw
                                     " — no registered implementation")}]})
   :repair nil})

;; Explicit, named pass-through for a phase that intentionally runs no blocking
;; gate. Distinct from an unknown gate (which fails closed above).
(register-gate! :noop)

(defmethod get-gate :noop
  [_]
  {:name :noop
   :description "Intentional pass-through (no gate)"
   :check (fn [_artifact _ctx] {:passed? true})
   :repair nil})

;------------------------------------------------------------------------------ Layer 2
;; Registry query

(defn list-gates
  "List all registered gate types.

   Returns:
     Set of gate keywords"
  []
  @registered-gates)

(defn gate-registered?
  "True when gate-kw resolves to its own get-gate implementation rather than the
   fail-closed :default. `get-method` returns the :default method for any
   unregistered keyword, so comparing against it detects a real registration.

   Callers that check gates defined in other namespaces (policy, behavioral, …)
   must load those implementations first (require `gate.interface`); a gate whose
   defmethod has not been loaded reads as unregistered."
  [gate-kw]
  (not= (get-method get-gate gate-kw)
        (get-method get-gate :default)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (list-gates)
  (get-gate :unknown-gate)
  :leave-this-here)
