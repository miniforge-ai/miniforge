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
(ns ai.miniforge.policy-clause.vocabulary
  "The vocabularies themselves: the policy severity scale, the
   enforcement-action set, and the mechanical gate scale they must not
   be confused with. Definition site only — checked operations live in
   `ai.miniforge.policy-clause.core`.")

;------------------------------------------------------------------------------ Layer 0

;; Vocabularies
(def ^{:stratum 0} severities
  "Canonical policy severity levels, most to least severe. The single
   source of truth for how-bad-is-it across the codebase: rule severity
   (policy-pack), runtime violation/attention severity (supervisory,
   evidence-bundle), and any display or rollup. One scale — not a
   per-producer vocabulary. Legacy `:major`/`:minor` map to
   `:high`/`:low` via `normalize-severity`."
  [:critical :high :medium :low :info])

(def ^{:stratum 0} enforcement-actions
  "Enforcement actions, strictest to most lenient, read as relaxation
   modes (Ariadne §13.1): `:hard-halt` = no relaxation path;
   `:require-approval` = relaxed only through an approval workflow;
   `:warn` and `:audit` = allowed, recorded as envelope obligations."
  [:hard-halt :require-approval :warn :audit])

(def ^{:stratum 0} mechanical-severities
  "The mechanical gate scale (`:error :warning :note :none`) owned by
   gate-classification. A DIFFERENT axis: tool exit semantics, not
   policy severity. It crosses into the policy scale only through the
   mapping fns in core."
  [:error :warning :note :none])

(defn ^{:stratum 0} normalize-severity
  "Coerce a legacy severity keyword to the canonical enum: `:major` →
   `:high`, `:minor` → `:low`; everything else is returned unchanged."
  [severity]
  (case severity
    :major :high
    :minor :low
    severity))

;------------------------------------------------------------------------------ Layer 1

;; Derived orders and schemas
(def ^{:stratum 1} severity-order
  "Severity -> rank, 0 = most severe. Lookup on an unknown severity
   returns nil — use `core/rank-severity` for the checked path."
  (zipmap severities (range)))

(def ^{:stratum 1} enforcement-order
  "Enforcement action -> rank, 0 = strictest."
  (zipmap enforcement-actions (range)))

(def ^{:stratum 1} Severity
  "Malli enum for a canonical policy severity."
  (into [:enum] severities))

(def ^{:stratum 1} EnforcementAction
  "Malli enum for an enforcement action."
  (into [:enum] enforcement-actions))

;------------------------------------------------------------------------------ Layer 2

;; Membership checks
(defn ^{:stratum 2} known-severity?
  "True when `severity` (after legacy normalization) is on the scale."
  [severity]
  (contains? severity-order (normalize-severity severity)))

(defn ^{:stratum 2} known-enforcement-action?
  "True when `action` is in the enforcement vocabulary."
  [action]
  (contains? enforcement-order action))
