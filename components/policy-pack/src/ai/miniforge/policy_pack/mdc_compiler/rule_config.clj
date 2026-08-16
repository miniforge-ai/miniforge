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
(ns ai.miniforge.policy-pack.mdc-compiler.rule-config
  "Rule-level config builders: detection config, remediation config
   (and its exclude-context lists), and the canonical set of
   frontmatter-opt-in enforcement actions. Split out of
   `ai.miniforge.policy-pack.mdc-compiler` (rule 210: slice 6/6, the
   final slice of the split train started in
   `mdc-compiler.frontmatter-values`/`mdc-compiler.frontmatter`/
   `mdc-compiler.condense`/`mdc-compiler.dewey`/`mdc-compiler.agent-behavior`,
   miniforge#1729/#1732/#1733/#1740/#1742 — same approach as the
   dag-orchestrator split, miniforge#1485, and the workflow-runner
   split, miniforge#1662). This was the second of the two independent
   chains feeding `mdc->rule`; removing it (the agent-behavior chain
   having already moved in slice 5) brings the parent namespace to
   budget."
  (:require
   [ai.miniforge.policy-pack.schema-types :as schema-types]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} build-exclude-context
  "Convert path/current exclude lists from MDC remediation config into
   ExcludeContext maps for the RuleRemediation schema."
  [remediation-map]
  (let [path-contains    (get remediation-map "excludePathContains")
        current-contains (get remediation-map "excludeCurrentContains")]
    (cond-> []
      (seq path-contains)
      (into (mapv (fn [p] {:path-contains p}) path-contains))

      (seq current-contains)
      (conj {:current-contains (vec current-contains)}))))

(defn ^{:stratum 0} build-detection-config
  "Build :rule/detection map from grouped frontmatter detection block.
   Returns {:type :content-scan ...} when detection config is present,
   {:type :custom} otherwise."
  [detection-map]
  (if (and detection-map (get detection-map "pattern"))
    (cond-> {:type    :content-scan
             :pattern (get detection-map "pattern")
             :mode    (keyword (get detection-map "mode" "positive"))}
      (get detection-map "emailPattern")
      (assoc :email-pattern (get detection-map "emailPattern")))
    {:type :custom}))

(def ^{:stratum 0} valid-enforcement-actions
  "Enforcement actions a rule may opt into via frontmatter `enforcement.action`,
   derived from the ONE canonical source (`schema-types/enforcement-actions`) so the
   compiler can never accept an action the rule schema rejects. `:hard-halt`
   makes a pack-derived gate BLOCK; the rest are non-blocking. An unrecognized
   value falls back to the alwaysApply default — a typo can't produce garbage."
  (set schema-types/enforcement-actions))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-remediation-config
  "Build :rule/remediation map from grouped frontmatter remediation block.
   Returns nil if no remediation config is present."
  [remediation-map]
  (when (and remediation-map (get remediation-map "strategy"))
    (let [excludes (build-exclude-context remediation-map)]
      (cond-> {:strategy (keyword (get remediation-map "strategy"))}
        (get remediation-map "type")
        (assoc :type (keyword (get remediation-map "type")))

        (get remediation-map "replacement")
        (assoc :replacement (get remediation-map "replacement"))

        (get remediation-map "template")
        (assoc :template (get remediation-map "template"))

        (some? (get remediation-map "autoFixable"))
        (assoc :auto-fixable-default (boolean (get remediation-map "autoFixable")))

        (seq excludes)
        (assoc :exclude-contexts excludes)))))
