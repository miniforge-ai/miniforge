;; Copyright 2025 miniforge.ai
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

(defonce ^:private registered-gates (atom #{}))

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

;; Default: unknown gates pass through with warning
(defmethod get-gate :default
  [gate-kw]
  {:name gate-kw
   :description "Unknown gate (pass-through)"
   :check (fn [_artifact _ctx]
            {:passed? true
             :warnings [{:type :unknown-gate
                         :message (str "Unknown gate: " gate-kw ", passing through")}]})
   :repair nil})

;------------------------------------------------------------------------------ Layer 2
;; Registry query

(defn list-gates
  "List all registered gate types.

   Returns:
     Set of gate keywords"
  []
  @registered-gates)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (list-gates)
  (get-gate :unknown-gate)
  :leave-this-here)
