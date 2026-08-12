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
(ns ai.miniforge.policy-pack.rules.pack-dependency-validation.versions
  "DateVer version parsing, comparison, and constraint satisfaction for
   pack dependency validation. Split out of
   `ai.miniforge.policy-pack.rules.pack-dependency-validation` (rule
   210, SL003: the combined namespace measured 6 real layers, max 3 —
   slice 1/3 of the split train). This is the self-contained version
   chain the parent's `detect-version-conflicts` sits on top of.

   Layer 0: parse-version, parse-version-constraint
   Layer 1: compare-versions (over parse-version)
   Layer 2: satisfies-constraint? (over parse-version-constraint +
     compare-versions)"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Version parsing and comparison
(defn ^{:stratum 0} parse-version
  "Parse a DateVer version string (YYYY.MM.DD or YYYY.MM.DD.N).
   Returns {:year int :month int :day int :patch int} or nil if invalid."
  [version-str]
  (when version-str
    (when-let [match (re-matches #"(\d{4})\.(\d{2})\.(\d{2})(?:\.(\d+))?" version-str)]
      (let [[_ year month day patch] match]
        {:year (Integer/parseInt year)
         :month (Integer/parseInt month)
         :day (Integer/parseInt day)
         :patch (if patch (Integer/parseInt patch) 0)}))))

(defn ^{:stratum 0} parse-version-constraint
  "Parse a version constraint string.
   Supports:
   - Exact: '2026.01.25' or '=2026.01.25'
   - Greater: '>2026.01.25', '>=2026.01.25'
   - Less: '<2026.01.25', '<=2026.01.25'
   - Range: '>=2026.01.01,<2026.02.01'
   - Wildcard: '2026.01.*'

   Returns {:type :exact/:range/:wildcard :constraints [...]}"
  [constraint-str]
  (cond
    ;; Range constraint (comma-separated)
    (str/includes? constraint-str ",")
    (let [parts (str/split constraint-str #",")
          trimmed (map str/trim parts)]
      {:type :range
       :constraints (mapv parse-version-constraint trimmed)})

    ;; Wildcard (e.g., "2026.01.*")
    (str/includes? constraint-str "*")
    {:type :wildcard
     :prefix (str/replace constraint-str #"\.\*$" "")}

    ;; Greater than or equal
    (str/starts-with? constraint-str ">=")
    {:type :gte
     :version (subs constraint-str 2)}

    ;; Greater than
    (str/starts-with? constraint-str ">")
    {:type :gt
     :version (subs constraint-str 1)}

    ;; Less than or equal
    (str/starts-with? constraint-str "<=")
    {:type :lte
     :version (subs constraint-str 2)}

    ;; Less than
    (str/starts-with? constraint-str "<")
    {:type :lt
     :version (subs constraint-str 1)}

    ;; Exact (with or without '=' prefix)
    :else
    {:type :exact
     :version (if (str/starts-with? constraint-str "=")
               (subs constraint-str 1)
               constraint-str)}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} compare-versions
  "Compare two version strings.
   Returns negative if v1 < v2, positive if v1 > v2, 0 if equal."
  [v1 v2]
  (let [p1 (parse-version v1)
        p2 (parse-version v2)]
    (if (and p1 p2)
      (let [year-cmp (compare (:year p1) (:year p2))]
        (if (not= 0 year-cmp)
          year-cmp
          (let [month-cmp (compare (:month p1) (:month p2))]
            (if (not= 0 month-cmp)
              month-cmp
              (let [day-cmp (compare (:day p1) (:day p2))]
                (if (not= 0 day-cmp)
                  day-cmp
                  (compare (:patch p1) (:patch p2))))))))
      (compare v1 v2))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} satisfies-constraint?
  "Check if a version satisfies a constraint.
   Returns true if version satisfies the constraint."
  [version constraint]
  (when (and version constraint)
    (let [parsed (parse-version-constraint constraint)]
      (case (:type parsed)
        :exact (= version (:version parsed))
        :gt (pos? (compare-versions version (:version parsed)))
        :gte (>= (compare-versions version (:version parsed)) 0)
        :lt (neg? (compare-versions version (:version parsed)))
        :lte (<= (compare-versions version (:version parsed)) 0)
        :wildcard (str/starts-with? version (:prefix parsed))
        :range (every? #(satisfies-constraint? version (str (:version %)))
                       (:constraints parsed))
        false))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test version parsing
  (parse-version "2026.01.25")
  ;; => {:year 2026 :month 1 :day 25 :patch 0}

  (parse-version "2026.01.25.2")
  ;; => {:year 2026 :month 1 :day 25 :patch 2}

  ;; Test version comparison
  (compare-versions "2026.01.25" "2026.01.26")
  ;; => -1

  ;; Test version constraints
  (satisfies-constraint? "2026.01.25" ">=2026.01.20")
  ;; => true

  (satisfies-constraint? "2026.01.25" "2026.01.*")
  ;; => true

  :leave-this-here)
