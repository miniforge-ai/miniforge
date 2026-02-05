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

(ns ai.miniforge.gate.syntax
  "Syntax validation gate.

   Checks that code artifacts parse without errors."
  (:require [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Syntax checking

(defn- parse-clojure
  "Attempt to parse Clojure code.

   Returns:
     {:valid? bool :error string?}"
  [code-str]
  (try
    (let [_ (read-string code-str)]
      {:valid? true})
    (catch Exception ex
      {:valid? false
       :error (ex-message ex)})))

(defn- check-syntax
  "Check syntax of artifact content.

   Arguments:
     artifact - Artifact with :content
     ctx      - Execution context

   Returns:
     {:passed? bool :errors [...]}"
  [artifact _ctx]
  (let [content (or (:content artifact)
                    (get-in artifact [:artifact/content])
                    "")
        content-str (if (string? content) content (pr-str content))
        result (parse-clojure content-str)]
    (if (:valid? result)
      {:passed? true}
      {:passed? false
       :errors [{:type :syntax-error
                 :message (:error result)
                 :location nil}]})))

(defn- repair-syntax
  "Attempt to repair syntax errors.

   Currently returns failure - syntax repair requires LLM."
  [artifact errors _ctx]
  {:success? false
   :artifact artifact
   :errors errors
   :message "Syntax repair requires LLM agent"})

;------------------------------------------------------------------------------ Layer 1
;; Registry

(registry/register-gate! :syntax)

(defmethod registry/get-gate :syntax
  [_]
  {:name :syntax
   :description "Validates code parses without syntax errors"
   :check check-syntax
   :repair repair-syntax})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Valid syntax
  (check-syntax {:content "(defn foo [] 42)"} {})
  ;; => {:passed? true}

  ;; Invalid syntax
  (check-syntax {:content "(defn foo [] 42"} {})
  ;; => {:passed? false :errors [...]}

  :leave-this-here)
