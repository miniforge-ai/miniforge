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

   Layer 0: Intent type definitions and inference
   Layer 1: Intent validation against resource counts
   Layer 2: Terraform plan and Kubernetes diff parsing

   Intent Types:
     :import   → Creates: 0, Updates: 0, Destroys: 0 (state-only)
     :create   → Creates: >0, Destroys: 0
     :update   → Creates: 0, Updates: >0, Destroys: 0
     :destroy  → Creates: 0, Updates: 0, Destroys: >0
     :refactor → Creates: 0, Updates: 0, Destroys: 0
     :migrate  → Creates: >0, Destroys: >0"
  (:require
   [ai.miniforge.policy-pack.detection :as detection]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Intent types and inference

(def intent-types
  "Valid intent type keywords."
  #{:import :create :update :destroy :refactor :migrate})

(defn infer-intent
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

;------------------------------------------------------------------------------ Layer 1
;; Intent validation

(def ^:private intent-constraints
  "For each declared intent, which change counts are allowed to be >0."
  {:import   {:creates false :updates false :destroys false}
   :create   {:creates true  :updates true  :destroys false}
   :update   {:creates false :updates true  :destroys false}
   :destroy  {:creates false :updates false :destroys true}
   :refactor {:creates false :updates false :destroys false}
   :migrate  {:creates true  :updates false :destroys true}})

(defn intent-matches?
  "Validate that declared intent matches actual resource change counts.

   Arguments:
   - declared — Declared intent keyword (e.g. :import, :create)
   - counts   — Map with :creates, :updates, :destroys

   Returns:
   - {:passed? true} if intent matches
   - {:passed? false :violations [...]} with violation details"
  [declared counts]
  (let [constraints (get intent-constraints declared)
        creates     (or (:creates counts) 0)
        updates     (or (:updates counts) 0)
        destroys    (or (:destroys counts) 0)]
    (if (nil? constraints)
      {:passed? true} ; unknown intent types pass by default
      (let [violations
            (cond-> []
              (and (not (:creates constraints))  (pos? creates))
              (conj {:field :creates :expected 0 :actual creates
                     :message (str "Intent :" (name declared)
                                   " does not allow creates, but found " creates)})

              (and (not (:updates constraints))  (pos? updates))
              (conj {:field :updates :expected 0 :actual updates
                     :message (str "Intent :" (name declared)
                                   " does not allow updates, but found " updates)})

              (and (not (:destroys constraints)) (pos? destroys))
              (conj {:field :destroys :expected 0 :actual destroys
                     :message (str "Intent :" (name declared)
                                   " does not allow destroys, but found " destroys)}))]
        (if (empty? violations)
          {:passed? true}
          {:passed? false :violations violations})))))

(defn semantic-intent-check
  "Full semantic intent validation check function per N4 §4.

   Arguments:
   - declared-intent — Keyword from intent-types
   - counts          — {:creates int :updates int :destroys int}

   Returns:
   - {:passed? bool :violations [...] :inferred-intent keyword :metadata {...}}"
  [declared-intent counts]
  (let [inferred (infer-intent counts)
        result   (intent-matches? declared-intent counts)]
    (assoc result
           :inferred-intent inferred
           :metadata {:declared declared-intent
                      :counts   counts})))

;------------------------------------------------------------------------------ Layer 2
;; Terraform plan parsing

(defn parse-terraform-plan-counts
  "Parse terraform plan output and return resource change counts.
   Delegates to detection/plan-resource-counts.

   Arguments:
   - plan-output — Raw terraform plan output string

   Returns:
   - {:creates int :updates int :destroys int}"
  [plan-output]
  (detection/plan-resource-counts plan-output))

;------------------------------------------------------------------------------ Layer 2
;; Kubernetes diff parsing

(defn parse-k8s-diff-counts
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

  ;; Full check
  (semantic-intent-check :import {:creates 0 :updates 0 :destroys 0})
  ;; => {:passed? true :inferred-intent :refactor :metadata {...}}

  :leave-this-here)
