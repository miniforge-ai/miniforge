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

(ns ai.miniforge.evidence-bundle.protocols.impl.semantic-validator
  "Implementation functions for SemanticValidator protocol.
   Validates that implementation matches declared intent."
  (:require
   [ai.miniforge.evidence-bundle.schema :as schema]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Terraform Plan Analysis

(defn parse-terraform-change-line
  "Parse a single Terraform plan line for resource changes.
   Returns {:type :create|:update|:destroy|:recreate|:import}"
  [line]
  (cond
    (str/includes? line " will be created")
    {:type :create}

    (str/includes? line " must be replaced")
    {:type :recreate :creates 1 :destroys 1}

    (str/includes? line " will be updated in-place")
    {:type :update}

    (str/includes? line " will be destroyed")
    {:type :destroy}

    (str/includes? line " will be imported")
    {:type :import}

    :else
    nil))

(defn analyze-terraform-plan-impl
  "Analyze Terraform plan artifact for resource changes.
   Returns {:creates N :updates N :destroys N}"
  [plan-artifact]
  (let [content (or (:artifact/content plan-artifact) "")
        lines (str/split-lines content)
        changes (keep parse-terraform-change-line lines)

        creates (count (filter #(= (:type %) :create) changes))
        updates (count (filter #(= (:type %) :update) changes))
        destroys (count (filter #(= (:type %) :destroy) changes))
        recreates (filter #(= (:type %) :recreate) changes)
        recreate-creates (reduce + 0 (map :creates recreates))
        recreate-destroys (reduce + 0 (map :destroys recreates))]

    {:creates (+ creates recreate-creates)
     :updates updates
     :destroys (+ destroys recreate-destroys)}))

;------------------------------------------------------------------------------ Layer 1
;; Kubernetes Manifest Analysis

(defn analyze-kubernetes-manifest-impl
  "Analyze Kubernetes manifest for resource changes.
   Returns {:creates N :updates N :destroys N}"
  [manifest-artifact]
  ;; Simplified implementation - count resources in manifest
  (let [content (or (:artifact/content manifest-artifact) "")
        ;; Count 'kind:' declarations as resources
        resources (count (re-seq #"(?m)^kind:" content))]
    {:creates resources
     :updates 0
     :destroys 0}))

;------------------------------------------------------------------------------ Layer 2
;; Semantic Validation Rules

(defn check-rule
  "Check if actual count matches rule.
   Rule can be: 0 (must be zero), :pos (must be positive), :any (any value)"
  [rule-value actual-count]
  (case rule-value
    0 (= 0 actual-count)
    :pos (> actual-count 0)
    :any true
    (= rule-value actual-count)))

(defn validate-intent-impl
  "Validate implementation matches declared intent.
   Returns {:passed? bool :violations [...]}"
  [intent implementation-artifacts]
  (let [intent-type (:intent/type intent)
        rules (get schema/semantic-validation-rules intent-type)
        violations (atom [])

        ;; Analyze all artifacts to count changes
        ;; This is simplified - in real implementation would analyze based on artifact type
        total-changes (reduce
                       (fn [acc artifact]
                         (let [analysis (cond
                                          (= (:artifact/type artifact) :terraform-plan)
                                          (analyze-terraform-plan-impl artifact)

                                          (= (:artifact/type artifact) :kubernetes-manifest)
                                          (analyze-kubernetes-manifest-impl artifact)

                                          :else
                                          {:creates 0 :updates 0 :destroys 0})]
                           (merge-with + acc analysis)))
                       {:creates 0 :updates 0 :destroys 0}
                       implementation-artifacts)

        creates (:creates total-changes)
        updates (:updates total-changes)
        destroys (:destroys total-changes)]

    ;; Check each rule
    (when-not (check-rule (:creates rules) creates)
      (swap! violations conj
             {:violation/rule-id "semantic-creates"
              :violation/severity :critical
              :violation/message (format "Intent '%s' expects %s creates, found %d"
                                         intent-type (:creates rules) creates)}))

    (when-not (check-rule (:updates rules) updates)
      (swap! violations conj
             {:violation/rule-id "semantic-updates"
              :violation/severity :high
              :violation/message (format "Intent '%s' expects %s updates, found %d"
                                         intent-type (:updates rules) updates)}))

    (when-not (check-rule (:destroys rules) destroys)
      (swap! violations conj
             {:violation/rule-id "semantic-destroys"
              :violation/severity :critical
              :violation/message (format "Intent '%s' expects %s destroys, found %d"
                                         intent-type (:destroys rules) destroys)}))

    {:passed? (empty? @violations)
     :violations @violations
     :semantic-validation/declared-intent intent-type
     :semantic-validation/actual-behavior (cond
                                            (and (> creates 0) (> destroys 0)) :migrate
                                            (> creates 0) :create
                                            (> updates 0) :update
                                            (> destroys 0) :destroy
                                            :else :import)
     :semantic-validation/resource-creates creates
     :semantic-validation/resource-updates updates
     :semantic-validation/resource-destroys destroys
     :semantic-validation/checked-at (java.time.Instant/now)}))
