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

(ns ai.miniforge.gate.interface
  "Gate registry interface.

   Gates are validation checkpoints with check and repair functions.
   Each gate type registers via defmethod, providing:
   - :check - (fn [artifact ctx] -> {:passed? bool :errors [...]}
   - :repair - (fn [artifact errors ctx] -> {:success? bool :artifact ...})

   Usage:
     (get-gate :syntax)
     (check-gate :lint artifact ctx)
     (repair-gate :lint artifact errors ctx)"
  (:require
   ;; Require implementations for side effects
   [ai.miniforge.gate.syntax]
   [ai.miniforge.gate.lint]
   [ai.miniforge.gate.test]
   [ai.miniforge.gate.policy]
   [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Re-export registry functions

(def get-gate
  "Get gate implementation for a keyword.

   Arguments:
     gate-kw - Gate keyword like :syntax, :lint, :tests-pass

   Returns:
     Gate map with :name, :check, :repair"
  registry/get-gate)

(def list-gates
  "List all registered gate types.

   Returns:
     Set of gate keywords"
  registry/list-gates)

;------------------------------------------------------------------------------ Layer 1
;; Gate operations

(defn check-gate
  "Run gate check on an artifact.

   Arguments:
     gate-kw  - Gate keyword
     artifact - Artifact to validate
     ctx      - Execution context

   Returns:
     {:passed? bool :errors [...] :gate gate-kw}"
  [gate-kw artifact ctx]
  (let [{:keys [check]} (get-gate gate-kw)]
    (try
      (let [result (check artifact ctx)]
        (assoc result :gate gate-kw))
      (catch Exception ex
        {:passed? false
         :gate gate-kw
         :errors [{:type :check-error
                   :message (ex-message ex)
                   :data (ex-data ex)}]}))))

(defn repair-gate
  "Attempt to repair gate failures.

   Arguments:
     gate-kw  - Gate keyword
     artifact - Artifact to repair
     errors   - Errors from check
     ctx      - Execution context

   Returns:
     {:success? bool :artifact ... :gate gate-kw}"
  [gate-kw artifact errors ctx]
  (let [{:keys [repair]} (get-gate gate-kw)]
    (if repair
      (try
        (let [result (repair artifact errors ctx)]
          (assoc result :gate gate-kw))
        (catch Exception ex
          {:success? false
           :gate gate-kw
           :artifact artifact
           :error {:message (ex-message ex)
                   :data (ex-data ex)}}))
      {:success? false
       :gate gate-kw
       :artifact artifact
       :error {:message "No repair function for gate"}})))

(defn check-gates
  "Run multiple gates on an artifact.

   Arguments:
     gate-kws - Vector of gate keywords
     artifact - Artifact to validate
     ctx      - Execution context

   Returns:
     {:all-passed? bool :results [...]}"
  [gate-kws artifact ctx]
  (let [results (mapv #(check-gate % artifact ctx) gate-kws)
        all-passed? (every? :passed? results)]
    {:all-passed? all-passed?
     :results results
     :failed-gates (filterv (complement :passed?) results)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; List available gates
  (list-gates)
  ;; => #{:syntax :lint :tests-pass :coverage :no-secrets ...}

  ;; Get a gate
  (get-gate :syntax)

  ;; Check a gate
  (check-gate :syntax {:content "(defn foo [])"} {})

  ;; Check multiple gates
  (check-gates [:syntax :lint] {:content "(defn foo [])"} {})

  :leave-this-here)
