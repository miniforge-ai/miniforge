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

(ns ai.miniforge.tui-views.update.filter
  "Filter palette for the PR fleet view (VS Code cmd+p style).

   Supports field-qualified queries with AND composition across fields
   and OR within the same field. Negation via '-' prefix.

   Grammar:
     query       = token*
     token       = neg-field | field | free-text
     neg-field   = '-' field-name ':' value
     field       = field-name ':' value
     free-text   = word (anything not field-qualified)
     field-name  = 'repo' | 'author' | 'readiness' | 'risk' | 'policy' | 'recommend'

   Examples:
     'repo:miniforge risk:high dark mode'
     'repo:a repo:b -risk:low'
     'recommend:merge author:alice'

   Layer 2."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-views.view.project :as project]))

;------------------------------------------------------------------------------ Layer 0
;; Query parsing

(def field-names
  "Recognized field qualifiers."
  #{"repo" "author" "readiness" "risk" "policy" "recommend"})

(defn parse-token
  "Parse a single whitespace-delimited token.
   Returns [:field field-name value], [:neg-field field-name value], or [:text word]."
  [token]
  (cond
    ;; Negated field: -risk:low
    (and (str/starts-with? token "-")
         (str/includes? (subs token 1) ":"))
    (let [rest (subs token 1)
          [field-name value] (str/split rest #":" 2)]
      (if (contains? field-names field-name)
        [:neg-field field-name value]
        [:text token]))

    ;; Field qualifier: risk:high
    (str/includes? token ":")
    (let [[field-name value] (str/split token #":" 2)]
      (if (contains? field-names field-name)
        [:field field-name value]
        [:text token]))

    ;; Free text
    :else
    [:text token]))

(defn parse-filter-query
  "Parse a filter query string into a structured filter map.

   Returns {:repo [\"a\" \"b\"]        ;; OR within field
            :risk [\"high\"]
            :neg-risk [\"low\"]         ;; negation
            :text \"dark mode\"}        ;; free-text joined"
  [query-str]
  (let [tokens (str/split (str/trim (or query-str "")) #"\s+")
        tokens (remove str/blank? tokens)]
    (reduce
     (fn [acc token]
       (let [[type field-or-word value] (parse-token token)]
         (case type
           :field     (update acc (keyword field-or-word) (fnil conj []) value)
           :neg-field (update acc (keyword (str "neg-" field-or-word)) (fnil conj []) value)
           :text      (update acc :text (fn [t]
                                           (if (str/blank? t)
                                             field-or-word
                                             (str t " " field-or-word)))))))
     {:text ""}
     tokens)))

;------------------------------------------------------------------------------ Layer 1
;; Matching

(defn fuzzy-match?
  "Case-insensitive substring match."
  [haystack needle]
  (str/includes? (str/lower-case (or haystack ""))
                 (str/lower-case (or needle ""))))

(defn any-match?
  "True if haystack matches any of the values (OR semantics)."
  [haystack values]
  (some #(fuzzy-match? haystack %) values))

(defn none-match?
  "True if haystack matches none of the values (negation)."
  [haystack values]
  (not (any-match? haystack values)))

(defn resolve-or
  "Try each path-fn in order, returning the first non-nil result or default."
  [default & path-fns]
  (or (some #(%) path-fns) default))

(defn pr-field-value
  "Extract the string value for a field from a PR for matching."
  [pr field-kw]
  (case field-kw
    :repo       (get pr :pr/repo "")
    :author     (get pr :pr/author "")
    :readiness  (name (resolve-or :unknown
                        #(get-in pr [:pr/readiness :readiness/state])
                        #(get-in (project/derive-readiness pr) [:readiness/state])))
    :risk       (name (resolve-or :unknown
                        #(get-in pr [:pr/risk :risk/level])
                        #(get-in (project/derive-risk pr) [:risk/level])))
    :policy     (case (get-in pr [:pr/policy :evaluation/passed?])
                  true "pass"
                  false "fail"
                  "unknown")
    :recommend  (name (resolve-or :wait
                        #(:action (project/derive-recommendation pr))))
    ""))

(defn positive-field?
  "True if the key is a positive (non-negated, non-text) field with values."
  [[field-kw values]]
  (and (not= field-kw :text)
       (not (str/starts-with? (name field-kw) "neg-"))
       (seq values)))

(defn negated-field?
  "True if the key is a negation field with values."
  [[field-kw values]]
  (and (str/starts-with? (name field-kw) "neg-")
       (seq values)))

(defn negated->real-field
  "Convert :neg-risk → :risk."
  [field-kw]
  (keyword (subs (name field-kw) 4)))

(defn matches-text? [pr text]
  (or (str/blank? text) (fuzzy-match? (:pr/title pr) text)))

(defn matches-positive-fields? [pr parsed-filter]
  (every? (fn [[field-kw values]]
            (any-match? (pr-field-value pr field-kw) values))
          (filter positive-field? parsed-filter)))

(defn matches-negated-fields? [pr parsed-filter]
  (every? (fn [[field-kw values]]
            (none-match? (pr-field-value pr (negated->real-field field-kw)) values))
          (filter negated-field? parsed-filter)))

(defn pr-matches-filter?
  "Test if a PR matches the parsed filter.
   AND composition across fields, OR within same field.
   Negation fields exclude matching items."
  [pr parsed-filter]
  (and (matches-text? pr (:text parsed-filter))
       (matches-positive-fields? pr parsed-filter)
       (matches-negated-fields? pr parsed-filter)))

;------------------------------------------------------------------------------ Layer 2
;; Index computation

(defn compute-filter-indices
  "Compute the set of matching PR indices for a filter query.
   Returns nil if query is blank (show all)."
  [prs query-str]
  (if (str/blank? query-str)
    nil
    (let [parsed (parse-filter-query query-str)]
      (if (and (str/blank? (:text parsed))
               (= 1 (count parsed)))
        ;; Empty parsed filter — show all
        nil
        (into #{}
              (keep-indexed (fn [idx pr]
                              (when (pr-matches-filter? pr parsed)
                                idx)))
              prs)))))
