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
(ns ai.miniforge.data-quality.invoke
  "Run one rule's check fn against one record. A check fn is caller-supplied
   code, so this namespace is the boundary where a throw or a malformed
   verdict becomes data. Neither may read as a pass, nor as a domain
   violation found by a rule that ran correctly.

   A well-formed verdict is a map with a boolean :valid?.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-with-exception-handling
  "Call `check-fn` on `record`. Returns [::returned verdict], or
   [::threw e] when it throws an Exception or an AssertionError (a failed
   `assert` in the rule). The tag wraps the return value, so no verdict the
   rule returns can select the exception path. Other JVM errors stay fatal.
   Plain try: data-quality has no slingshot dependency (rule 211)."
  [check-fn record]
  (try
    [::returned (check-fn record)]
    (catch Exception e
      [::threw e])
    (catch AssertionError e
      [::threw e])))

(defn- ^{:stratum 0} verdict-evaluation
  "The evaluation for a well-formed verdict. :rule/error is reserved for
   errors this boundary records, so a verdict's own :rule/error is dropped."
  [base verdict]
  (merge base (dissoc verdict :rule/error)))

(defn- ^{:stratum 0} well-formed-verdict? [verdict]
  (and (map? verdict) (boolean? (:valid? verdict))))

(defn- ^{:stratum 0} thrown-evaluation
  "The evaluation for a rule whose check fn threw `e`."
  [base e]
  (assoc base
         :valid? false
         :rule/error {:error/kind    :rule/threw
                      :error/type    (.getName (class e))
                      :error/message (ex-message e)}))

(defn- ^{:stratum 0} malformed-evaluation
  "The evaluation for a rule whose check fn returned something other than a
   well-formed verdict. Records the verdict's type, not its value, so record
   contents do not leak into the error."
  [base verdict]
  (assoc base
         :valid? false
         :rule/error {:error/kind         :rule/malformed-verdict
                      :error/verdict-type (str (type verdict))}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} evaluate-rule
  "Evaluate one rule against one record: the verdict merged over
   {:rule/id :field :severity}. When the check fn throws, or returns anything
   but a well-formed verdict, the evaluation has :valid? false and a
   :rule/error describing what went wrong."
  [{:rule/keys [id field severity check-fn]} record]
  (let [base             {:rule/id id :field field :severity severity}
        [outcome result] (check-with-exception-handling check-fn record)]
    (cond
      (= ::threw outcome)           (thrown-evaluation base result)
      (well-formed-verdict? result) (verdict-evaluation base result)
      :else                         (malformed-evaluation base result))))

(comment
  (evaluate-rule {:rule/id :r :rule/check-fn (fn [_] {:valid? "yes"})} {:id 1}))
