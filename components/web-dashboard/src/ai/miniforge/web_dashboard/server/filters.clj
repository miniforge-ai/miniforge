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

(ns ai.miniforge.web-dashboard.server.filters
  "Filter parsing and normalization for request parameters."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.web-dashboard.filters-new :as filters]))

;------------------------------------------------------------------------------ Layer 0
;; Parameter parsing utilities

(defn param-value
  "Read request parameter by keyword or string key."
  [params key default]
  (or (get params key)
      (when (keyword? key) (get params (name key)))
      (when (string? key) (get params (keyword key)))
      default))

(defn ->keyword
  "Convert string/keyword value to keyword when possible."
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [trimmed (str/trim v)
                      cleaned (if (str/starts-with? trimmed ":")
                                (subs trimmed 1)
                                trimmed)]
                  (when-not (str/blank? cleaned)
                    (keyword cleaned)))
    :else nil))

(defn- parse-bool
  "Parse boolean-like string values."
  [v]
  (cond
    (boolean? v) v
    (string? v) (case (str/lower-case (str/trim v))
                  "true" true
                  "false" false
                  v)
    :else v))

(defn- decode-url-part
  "Decode a URL query-string key/value."
  [s]
  (java.net.URLDecoder/decode (str s) "UTF-8"))

(defn query-string->params
  "Parse raw query-string into a string-keyed map."
  [query-string]
  (if (str/blank? query-string)
    {}
    (reduce
     (fn [acc pair]
       (let [[k v] (str/split pair #"=" 2)
             key (decode-url-part k)
             value (decode-url-part (or v ""))]
         (assoc acc key value)))
     {}
     (remove str/blank? (str/split query-string #"&")))))

;------------------------------------------------------------------------------ Layer 1
;; Operator and value normalization

(defn- normalize-op
  "Normalize operation token to keyword."
  [op]
  (let [token (str/lower-case (str/trim (str op)))]
    (case token
      ":=" :=
      "=" :=
      ":!=" :!=
      "!=" :!=
      ":in" :in
      "in" :in
      ":contains" :contains
      "contains" :contains
      ":text-search" :text-search
      "text-search" :text-search
      ":<" :<
      "<" :<
      ":>" :>
      ">" :>
      ":<=" :<=
      "<=" :<=
      ":>=" :>=
      ">=" :>=
      ":between" :between
      "between" :between
      :=)))

(defn- normalize-ast-op
  "Normalize AST boolean operator."
  [op]
  (let [token (str/lower-case (str/trim (str op)))]
    (case token
      ":and" :and
      "and" :and
      ":or" :or
      "or" :or
      ":not" :not
      "not" :not
      :and)))

(defn- normalize-filter-value
  "Coerce clause value based on filter spec type/value configuration."
  [spec value]
  (let [filter-type (:filter/type spec)
        filter-values (:filter/values spec)]
    (cond
      (= filter-type :bool)
      (parse-bool value)

      (and (= filter-type :enum)
           (vector? filter-values)
           (every? keyword? filter-values)
           (string? value))
      (or (some #(when (= (name %) (str/replace value #"^:" "")) %) filter-values)
          value)

      (and (= filter-type :enum)
           (string? value)
           (str/starts-with? (str/trim value) ":"))
      (->keyword value)

      :else value)))

(defn- normalize-filter-clause
  "Normalize a single JSON clause to evaluator-friendly shape."
  [clause]
  (let [filter-id (->keyword (or (:filter/id clause)
                                 (get clause "filter/id")))
        spec (when filter-id (filters/get-filter-spec-by-id filter-id))
        value (or (:value clause) (get clause "value"))
        op (or (:op clause) (get clause "op"))]
    {:filter/id filter-id
     :op (normalize-op op)
     :value (if spec
              (normalize-filter-value spec value)
              value)}))

;------------------------------------------------------------------------------ Layer 2
;; AST parsing

(defn- normalize-filter-ast
  "Normalize JSON AST from browser before evaluation."
  [ast]
  (let [clauses (or (:clauses ast) (get ast "clauses") [])]
    {:op (normalize-ast-op (or (:op ast) (get ast "op")))
     :clauses (->> clauses
                   (map normalize-filter-clause)
                   (filter :filter/id)
                   vec)}))

(defn parse-filter-ast
  "Parse filter AST from request parameters.

   Expects JSON-encoded filter AST in 'filters' parameter."
  [params]
  (try
    (when-let [filters-json (param-value params :filters nil)]
      (normalize-filter-ast
       (if (string? filters-json)
         (json/parse-string filters-json true)
         filters-json)))
    (catch Exception e
      (println "Error parsing filter AST:" (.getMessage e))
      nil)))
