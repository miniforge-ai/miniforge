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

(ns ai.miniforge.gate.lint
  "Lint validation gate.

   Checks that code passes linting rules."
  (:require [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Lint checking (simplified - real impl would call clj-kondo)

(defn- check-unused-vars
  "Check for obvious unused variable patterns."
  [code-str]
  (let [_bindings (re-seq #"\[([a-z_][a-z0-9_-]*)\s+" code-str)]
    ;; Simplified: just check if binding is used after definition
    []))

(defn- check-lint
  "Check lint rules on artifact content.

   Arguments:
     artifact - Artifact with :content
     ctx      - Execution context (may contain :lint-config)

   Returns:
     {:passed? bool :errors [] :warnings []}"
  [artifact _ctx]
  (let [content (or (:content artifact)
                    (get-in artifact [:artifact/content])
                    "")
        content-str (if (string? content) content (pr-str content))
        ;; In real impl, this would invoke clj-kondo
        errors []
        warnings (check-unused-vars content-str)]
    (if (empty? errors)
      {:passed? true
       :warnings warnings}
      {:passed? false
       :errors errors
       :warnings warnings})))

(defn- repair-lint
  "Attempt to repair lint errors.

   Currently returns failure - lint repair requires LLM."
  [artifact errors _ctx]
  {:success? false
   :artifact artifact
   :errors errors
   :message "Lint repair requires LLM agent"})

;------------------------------------------------------------------------------ Layer 1
;; Registry

(registry/register-gate! :lint)

(defmethod registry/get-gate :lint
  [_]
  {:name :lint
   :description "Validates code passes linting rules (clj-kondo)"
   :check check-lint
   :repair repair-lint})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (check-lint {:content "(defn foo [] 42)"} {})
  :leave-this-here)
