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
(ns ai.miniforge.policy-clause.core
  "Checked operations over the policy-clause vocabularies (Ariadne step
   1a, RFC docs/architecture/rfc-ariadne-adoption.md). Unknown values
   are anomalies, never defaults: the pre-Ariadne code sorted unknown
   severities last (rank 99) and let unknown enforcement actions fall
   through classification filters — both fail-open paths this component
   makes unrepresentable. Cross-namespace vocabulary lookups do not
   participate in the intra-namespace layering model."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.policy-clause.vocabulary :as voc]))

;------------------------------------------------------------------------------ Layer 0

;; Checked lookups and axis crossings
(defn ^{:stratum 0} rank-severity
  "Rank of a severity (0 = most severe), or an `:invalid-input` anomaly
   (subtype `:anomalies.policy-clause/unknown-severity`). Never a
   default rank — the pre-Ariadne sort-last behavior is the fail-open
   this replaces."
  [severity]
  (let [s (voc/normalize-severity severity)]
    (or (get voc/severity-order s)
        (anomaly/sub-anomaly :invalid-input
                             :anomalies.policy-clause/unknown-severity
                             (str "Unknown policy severity " (pr-str severity)
                                  "; expected one of " (pr-str voc/severities))
                             {:severity severity}))))

(defn ^{:stratum 0} mechanical->severity
  "Map a mechanical gate value onto the policy scale (`:error` →
   `:high`, `:warning` → `:medium`, `:note` → `:info`). `:none` carries
   no finding and has no policy severity; it and any unknown value
   return an anomaly. The ONLY crossing between the two axes."
  [mechanical]
  (case mechanical
    :error :high
    :warning :medium
    :note :info
    (anomaly/sub-anomaly :invalid-input
                         :anomalies.policy-clause/unknown-mechanical-severity
                         (str "No policy severity for mechanical value "
                              (pr-str mechanical))
                         {:mechanical mechanical})))

(defn ^{:stratum 0} severity->attention
  "Map a policy severity onto the attention scale ([:critical :warning
   :info] — the console's three-level axis): `:critical`/`:high` →
   `:critical`, `:medium`/`:low` → `:warning`, `:info` → `:info`.
   Unknown values return an anomaly. Lives here with the other axis
   crossings — consumers must not carry their own severity maps."
  [severity]
  (case (voc/normalize-severity severity)
    (:critical :high) :critical
    (:medium :low) :warning
    :info :info
    (anomaly/sub-anomaly :invalid-input
                         :anomalies.policy-clause/unknown-severity
                         (str "No attention severity for policy severity "
                              (pr-str severity))
                         {:severity severity})))

(defn ^{:stratum 0} severity->mechanical
  "Map a policy severity onto the mechanical gate scale (`:critical`/
   `:high` → `:error`, `:medium`/`:low` → `:warning`, `:info` →
   `:note`). Unknown values return an anomaly. The inverse crossing —
   both directions live here and nowhere else."
  [severity]
  (case (voc/normalize-severity severity)
    (:critical :high) :error
    (:medium :low) :warning
    :info :note
    (anomaly/sub-anomaly :invalid-input
                         :anomalies.policy-clause/unknown-severity
                         (str "No mechanical value for policy severity "
                              (pr-str severity))
                         {:severity severity})))

;------------------------------------------------------------------------------ Layer 1

;; Comparison and sorting built on the checked rank
(defn ^{:stratum 1} compare-severity
  "Compare two severities: negative when `a` is more severe, positive
   when less, 0 when equal. Returns an `:invalid-input` anomaly when
   either value is off the scale."
  [a b]
  (let [ra (rank-severity a)
        rb (rank-severity b)]
    (cond
      (anomaly/anomaly? ra) ra
      (anomaly/anomaly? rb) rb
      :else (- ra rb))))

(defn ^{:stratum 1} sort-by-severity
  "Sort `items` most-severe-first by `(severity-fn item)`. Validates
   every severity first: any unknown value yields an anomaly instead of
   a silently misplaced item."
  [severity-fn items]
  (let [ranks (map (comp rank-severity severity-fn) items)
        bad (first (filter anomaly/anomaly? ranks))]
    (or bad
        (->> (map vector ranks items)
             (sort-by first)
             (mapv second)))))

;------------------------------------------------------------------------------ Layer 2

;; Selection built on comparison
(defn ^{:stratum 2} more-severe
  "The more severe of two severities, or an anomaly when either is
   unknown."
  [a b]
  (let [c (compare-severity a b)]
    (cond
      (anomaly/anomaly? c) c
      (pos? c) b
      :else a)))
