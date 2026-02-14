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

   Layer 0: Tier definitions
   Layer 1: Tier enforcement functions")

;------------------------------------------------------------------------------ Layer 0
;; Tier definitions

(def tier-definitions
  "Automation tier constraint definitions."
  {:tier-0 {:auto-approve? false
            :auto-merge?   false
            :description   "Human-only: no automation"
            :constraints   {}}
   :tier-1 {:auto-approve? false
            :auto-merge?   true
            :description   "Human approves, system merges"
            :constraints   {}}
   :tier-2 {:auto-approve? true
            :auto-merge?   true
            :description   "Full automation with tight guardrails"
            :constraints   {:max-risk-level :medium
                            :min-readiness  0.90}}
   :tier-3 {:auto-approve? true
            :auto-merge?   true
            :description   "Full automation with relaxed guardrails"
            :constraints   {:max-risk-level :high
                            :min-readiness  0.75}}})

(def ^:private risk-level-order
  "Risk levels ordered from lowest to highest."
  {:low 0 :medium 1 :high 2 :critical 3})

;------------------------------------------------------------------------------ Layer 1
;; Tier enforcement functions

(defn risk-within-limit?
  "Check if a risk level is within the tier's maximum allowed level."
  [actual-level max-level]
  (<= (get risk-level-order actual-level 99)
      (get risk-level-order max-level 99)))

(defn tier-allows?
  "Check if a tier allows a specific operation given readiness and risk.

   Arguments:
   - tier - Keyword :tier-0 through :tier-3
   - operation - Keyword :auto-approve or :auto-merge
   - readiness - Readiness score (0.0–1.0)
   - risk - Risk map with :risk/level keyword

   Returns: boolean"
  [tier operation readiness risk]
  (let [tier-def (get tier-definitions tier)
        risk-level (or (:risk/level risk) :low)]
    (when tier-def
      (case operation
        :auto-approve
        (and (:auto-approve? tier-def)
             (let [{:keys [max-risk-level min-readiness]} (:constraints tier-def)]
               (and (or (nil? max-risk-level)
                        (risk-within-limit? risk-level max-risk-level))
                    (or (nil? min-readiness)
                        (>= readiness min-readiness)))))

        :auto-merge
        (and (:auto-merge? tier-def)
             (let [{:keys [max-risk-level min-readiness]} (:constraints tier-def)]
               (and (or (nil? max-risk-level)
                        (risk-within-limit? risk-level max-risk-level))
                    (or (nil? min-readiness)
                        (>= readiness min-readiness)))))

        ;; Unknown operation
        false))))

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
  [tier readiness risk]
  (tier-allows? tier :auto-approve readiness risk))

(defn can-auto-merge?
  "Convenience: check if a PR can be auto-merged given its tier, readiness, and risk."
  [tier readiness risk]
  (tier-allows? tier :auto-merge readiness risk))

(defn get-automation-tier
  "Get the effective automation tier with full definition.

   Arguments:
   - repo-config - Repository configuration map
   - repo-id - Repository identifier

   Returns: {:tier kw :definition map}"
  [repo-config repo-id]
  (let [tier (get-repo-tier repo-config repo-id)]
    {:tier tier
     :definition (get tier-definitions tier)}))

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

  ;; Tier 3: both, relaxed constraints
  (tier-allows? :tier-3 :auto-approve 0.80 {:risk/level :high})   ;; => true
  (tier-allows? :tier-3 :auto-approve 0.80 {:risk/level :critical}) ;; => false

  ;; Config lookup
  (get-repo-tier {"acme/terraform" {:automation-tier :tier-2}} "acme/terraform")
  ;; => :tier-2
  (get-repo-tier {} "unknown-repo")
  ;; => :tier-1 (default)

  :leave-this-here)
