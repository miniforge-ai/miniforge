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
(ns ai.miniforge.policy-pack.enforcement
  "Severity and enforcement-action comparison.

   Split out of core.clj (Wave 2, SL003): the pure comparison chain that
   `builders/merge-rules` escalates over — severity/enforcement ordering,
   pairwise comparison, and picking the stricter of two actions.

   Layer 0: enforcement-order/compare-severity/more-severe — pass-throughs
     to the shared vocabulary (policy-clause via schema)
   Layer 1: compare-enforcement (over enforcement-order)
   Layer 2: stricter-enforcement (over compare-enforcement)"
  (:require
   [ai.miniforge.schema.interface :as shared]))

;------------------------------------------------------------------------------ Layer 0

;; Severity and enforcement comparison
(def ^{:stratum 0} enforcement-order
  "Enforcement actions from strictest to most lenient — pass-through to
   the shared vocabulary (policy-clause via schema)."
  shared/enforcement-order)

(def ^{:stratum 0} compare-severity
  "Compare two severities by the shared canonical order (negative if a is more
   severe, positive if b is, 0 if equal)."
  shared/compare-severity)

(def ^{:stratum 0} more-severe
  "Return the more severe of two severities (shared canonical order)."
  shared/more-severe)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} compare-enforcement
  "Compare two enforcement actions.
   Returns negative if a is stricter, positive if b is stricter, 0 if equal."
  [a b]
  (- (get enforcement-order a 99)
     (get enforcement-order b 99)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} stricter-enforcement
  "Return the stricter of two enforcement actions."
  [a b]
  (if (neg? (compare-enforcement a b)) a b))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test severity comparison
  (compare-severity :critical :high)  ;; => -1 (critical more severe)
  (more-severe :low :high)          ;; => :high

  ;; Test enforcement comparison
  (compare-enforcement :hard-halt :warn)  ;; => -2 (hard-halt stricter)
  (stricter-enforcement :warn :require-approval)  ;; => :require-approval

  :leave-this-here)
