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
(ns ai.miniforge.policy-pack.intent
  "Semantic intent validation — enforce match between declared intent
   and actual implementation behavior (N4 §4).

   Layer 0: intent-types, infer-intent, intent-constraints,
     parse-terraform-plan-counts, parse-k8s-diff-counts
   Layer 1: intent-violation (over intent-constraints)
   Layer 2: intent-matches? (over intent-violation)

   The full semantic check that composes `infer-intent` and
   `intent-matches?` into one pass/fail result is a 4th layer, split
   out to `ai.miniforge.policy-pack.intent.check` (rule 210: this
   namespace measured 4 real layers, over the budget of 3).

   Intent Types:
     :import   → Creates: 0, Updates: 0, Destroys: 0 (state-only)
     :create   → Creates: >0, Destroys: 0
     :update   → Creates: 0, Updates: >0, Destroys: 0
     :destroy  → Creates: 0, Updates: 0, Destroys: >0
     :refactor → Creates: 0, Updates: 0, Destroys: 0
     :migrate  → Creates: >0, Destroys: >0"
  (:require
   [ai.miniforge.policy-pack.detection.matching :as matching]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Intent types and inference
(def ^{:stratum 0} intent-types
  "Valid intent type keywords."
  #{:import :create :update :destroy :refactor :migrate})

(defn ^{:stratum 0} infer-intent
  "Infer the intent type from resource change counts.

   Arguments:
   - counts — Map with :creates, :updates, :destroys (all ints)

   Returns:
   - Intent keyword, or :mixed if no clear pattern."
  [{:keys [creates updates destroys]}]
  (let [creates  (or creates 0)
        updates  (or updates 0)
        destroys (or destroys 0)]
    (cond
      (and (zero? creates) (zero? updates) (zero? destroys))  :refactor
      (and (pos? creates)  (zero? updates) (zero? destroys))  :create
      (and (zero? creates) (pos? updates)  (zero? destroys))  :update
      (and (zero? creates) (zero? updates) (pos? destroys))   :destroy
      (and (pos? creates)  (zero? updates) (pos? destroys))   :migrate
      :else                                                    :mixed)))

;; Intent validation
(def ^{:stratum 0} ^:private violation-message-fmt
  "Format string for intent violation messages."
  "Intent :%s does not allow %s, but found %d")

(def ^{:stratum 0} ^:private intent-constraints
  "For each declared intent, which change counts are allowed to be >0."
  {:import   {:creates false :updates false :destroys false}
   :create   {:creates true  :updates true  :destroys false}
   :update   {:creates false :updates true  :destroys false}
   :destroy  {:creates false :updates false :destroys true}
   :refactor {:creates false :updates false :destroys false}
   :migrate  {:creates true  :updates false :destroys true}})

;; Terraform plan parsing
(defn ^{:stratum 0} parse-terraform-plan-counts
  "Parse terraform plan output and return resource change counts.
   Delegates to matching/plan-resource-counts (added by PR #457).

   Arguments:
   - plan-output — Raw terraform plan output string

  Returns:
   - {:creates int :updates int :destroys int}"
  [plan-output]
  (matching/plan-resource-counts plan-output))

;; Kubernetes diff parsing
(defn ^{:stratum 0} parse-k8s-diff-counts
  "Parse kubectl diff output and return resource change counts.

   Detects:
   - Creates: lines starting with '+ ' that aren't '+++'
   - Destroys: lines starting with '- ' that aren't '---'
   - Updates: files with both additions and removals

   Arguments:
   - diff-output — Raw kubectl diff output string

   Returns:
   - {:creates int :updates int :destroys int}"
  [diff-output]
  (if (str/blank? diff-output)
    {:creates 0 :updates 0 :destroys 0}
    (let [lines     (str/split-lines diff-output)
          additions (count (filter #(and (str/starts-with? % "+ ")
                                        (not (str/starts-with? % "+++"))
                                        (not (str/starts-with? % "+++ "))) lines))
          deletions (count (filter #(and (str/starts-with? % "- ")
                                        (not (str/starts-with? % "---"))
                                        (not (str/starts-with? % "--- "))) lines))]
      ;; Heuristic: pure additions = creates, pure deletions = destroys,
      ;; mixed = updates
      (cond
        (and (pos? additions) (zero? deletions))
        {:creates additions :updates 0 :destroys 0}

        (and (zero? additions) (pos? deletions))
        {:creates 0 :updates 0 :destroys deletions}

        (and (pos? additions) (pos? deletions))
        {:creates 0 :updates (min additions deletions) :destroys 0}

        :else
        {:creates 0 :updates 0 :destroys 0}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} intent-violation
  "Build an intent violation map for a disallowed field."
  [declared field-name actual]
  {:field    field-name
   :expected 0
   :actual   actual
   :message  (format violation-message-fmt (name declared) (name field-name) actual)})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} intent-matches?
  "Validate that declared intent matches actual resource change counts.

   Arguments:
   - declared — Declared intent keyword (e.g. :import, :create)
   - counts   — Map with :creates, :updates, :destroys

   Returns:
   - {:passed? true} if intent matches
   - {:passed? false :violations [...]} with violation details"
  [declared counts]
  (let [constraints (get intent-constraints declared)
        creates     (get counts :creates 0)
        updates     (get counts :updates 0)
        destroys    (get counts :destroys 0)]
    (if (nil? constraints)
      {:passed? true} ; unknown intent types pass by default
      (let [violations
            (cond-> []
              (and (not (:creates constraints))  (pos? creates))
              (conj (intent-violation declared :creates creates))

              (and (not (:updates constraints))  (pos? updates))
              (conj (intent-violation declared :updates updates))

              (and (not (:destroys constraints)) (pos? destroys))
              (conj (intent-violation declared :destroys destroys)))]
        (if (empty? violations)
          {:passed? true}
          {:passed? false :violations violations})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Infer intent from counts
  (infer-intent {:creates 5 :updates 0 :destroys 0})
  ;; => :create

  (infer-intent {:creates 0 :updates 0 :destroys 0})
  ;; => :refactor

  ;; Validate declared vs actual
  (intent-matches? :import {:creates 3 :updates 0 :destroys 0})
  ;; => {:passed? false :violations [...]}

  (intent-matches? :create {:creates 3 :updates 1 :destroys 0})
  ;; => {:passed? true}

  ;; Full check lives in ai.miniforge.policy-pack.intent.check

  :leave-this-here)
