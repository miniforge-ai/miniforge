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

(ns ai.miniforge.pr-train.tiers
  "Automation tier enforcement for PR trains.

   Defines four automation tiers controlling auto-approve and auto-merge
   capabilities based on risk and readiness thresholds.

   | Tier | Auto-approve? | Auto-merge? | Constraints                        |
   |------|---------------|-------------|------------------------------------|
   | 0    | no            | no          | Human-only                         |
   | 1    | no            | yes         | Human approves, system merges      |
   | 2    | yes           | yes         | Risk <= medium, readiness >= 0.90  |
   | 3    | yes           | yes         | Risk <= high, readiness >= 0.75    |

   Tier definitions are loaded from resources/config/governance/tiers.edn
   and can be overridden by passing a custom definitions map to `tier-allows?`
   and related functions.

   Layer 0: Tier definitions (data)
   Layer 1: Tier enforcement functions"
  (:require
   [ai.miniforge.config.interface :as config]))

;------------------------------------------------------------------------------ Layer 0
;; Tier definitions — pure data, no code

(def default-tier-definitions
  "Default automation tier constraint definitions loaded from
   resources/config/governance/tiers.edn."
  (config/load-governance-config :tiers))

;; Backward-compatible alias
(def tier-definitions
  "Automation tier constraint definitions."
  default-tier-definitions)

(def risk-level-order
  "Risk levels ordered from lowest to highest."
  {:low 0 :medium 1 :high 2 :critical 3})

;------------------------------------------------------------------------------ Layer 1
;; Tier enforcement functions

(defn risk-within-limit?
  "Check if a risk level is within the tier's maximum allowed level."
  [actual-level max-level]
  (<= (get risk-level-order actual-level 99)
      (get risk-level-order max-level 99)))

(defn check-operation
  "Check if a tier definition allows an operation given readiness and risk."
  [tier-def operation-key readiness risk-level]
  (and (get tier-def operation-key)
       (let [{:keys [max-risk-level min-readiness]} (:constraints tier-def)]
         (and (or (nil? max-risk-level)
                  (risk-within-limit? risk-level max-risk-level))
              (or (nil? min-readiness)
                  (>= readiness min-readiness))))))

(defn tier-allows?
  "Check if a tier allows a specific operation given readiness and risk.

   Arguments:
   - tier - Keyword :tier-0 through :tier-3 (or custom)
   - operation - Keyword :auto-approve or :auto-merge
   - readiness - Readiness score (0.0–1.0)
   - risk - Risk map with :risk/level keyword
   - tiers - Optional tier definitions map (default: default-tier-definitions)

   Returns: boolean"
  ([tier operation readiness risk]
   (tier-allows? tier operation readiness risk default-tier-definitions))
  ([tier operation readiness risk tiers]
   (let [tier-def (get tiers tier)
         risk-level (or (:risk/level risk) :low)]
     (when tier-def
       (case operation
         :auto-approve (check-operation tier-def :auto-approve? readiness risk-level)
         :auto-merge   (check-operation tier-def :auto-merge? readiness risk-level)
         false)))))

(defn get-repo-tier
  "Look up the automation tier for a repository.

   Arguments:
   - repo-config - Map of repo-id -> config with :automation-tier
   - repo-id - Repository identifier string

   Returns: Keyword :tier-0 through :tier-3 (default: :tier-1)"
  [repo-config repo-id]
  (get-in repo-config [repo-id :automation-tier] :tier-1))

(defn can-auto-approve?
  "Convenience: check if a PR can be auto-approved given its tier, readiness, and risk."
  ([tier readiness risk]
   (tier-allows? tier :auto-approve readiness risk))
  ([tier readiness risk tiers]
   (tier-allows? tier :auto-approve readiness risk tiers)))

(defn can-auto-merge?
  "Convenience: check if a PR can be auto-merged given its tier, readiness, and risk."
  ([tier readiness risk]
   (tier-allows? tier :auto-merge readiness risk))
  ([tier readiness risk tiers]
   (tier-allows? tier :auto-merge readiness risk tiers)))

(defn get-automation-tier
  "Get the effective automation tier with full definition.

   Arguments:
   - repo-config - Repository configuration map
   - repo-id - Repository identifier
   - tiers - Optional tier definitions map

   Returns: {:tier kw :definition map}"
  ([repo-config repo-id]
   (get-automation-tier repo-config repo-id default-tier-definitions))
  ([repo-config repo-id tiers]
   (let [tier (get-repo-tier repo-config repo-id)]
     {:tier tier
      :definition (get tiers tier)})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Tier 0: nothing auto
  (tier-allows? :tier-0 :auto-merge 1.0 {:risk/level :low})    ;; => false
  (tier-allows? :tier-0 :auto-approve 1.0 {:risk/level :low})  ;; => false

  ;; Tier 1: auto-merge but not auto-approve
  (tier-allows? :tier-1 :auto-merge 0.5 {:risk/level :low})    ;; => true
  (tier-allows? :tier-1 :auto-approve 1.0 {:risk/level :low})  ;; => false

  ;; Tier 2: both, but tight constraints
  (tier-allows? :tier-2 :auto-approve 0.95 {:risk/level :low})    ;; => true
  (tier-allows? :tier-2 :auto-approve 0.80 {:risk/level :low})    ;; => false (below 0.90)
  (tier-allows? :tier-2 :auto-approve 0.95 {:risk/level :high})   ;; => false (above medium)

  ;; Custom tier definitions
  (tier-allows? :tier-2 :auto-approve 0.80 {:risk/level :low}
                (assoc-in default-tier-definitions [:tier-2 :constraints :min-readiness] 0.75))
  ;; => true (relaxed readiness to 0.75)

  ;; Config lookup
  (get-repo-tier {"acme/terraform" {:automation-tier :tier-2}} "acme/terraform")
  ;; => :tier-2

  :leave-this-here)
