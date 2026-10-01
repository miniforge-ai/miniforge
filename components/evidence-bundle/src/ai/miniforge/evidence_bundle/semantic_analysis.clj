;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.semantic-analysis
  "Resource-count extraction for semantic intent checks."
  (:require [clojure.string :as str]
            [ai.miniforge.evidence-bundle.semantic-rules :as rules]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} artifact-content [artifact]
  ;; Optional nil content historically represents an empty artifact.
  (let [content (:artifact/content artifact)]
    (if (nil? content) "" content)))

(defn- ^{:stratum 0} resource-counts [changes]
  (let [counts (frequencies (map :type changes))
        replacements (get counts :recreate 0)
        creates (+ (get counts :create 0) replacements)
        updates (get counts :update 0)
        destroys (+ (get counts :destroy 0) replacements)]
    {:creates creates
     :updates updates
     :destroys destroys}))

(defn ^{:stratum 0} parse-terraform-change-line
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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} analyze-kubernetes-manifest-impl
  "Analyze Kubernetes manifest for resource changes.
   Returns {:creates N :updates N :destroys N}"
  [manifest-artifact]
  ;; Simplified implementation - count resources in manifest
  (let [content (artifact-content manifest-artifact)
        ;; Count 'kind:' declarations as resources
        resources (count (re-seq #"(?m)^kind:" content))]
    {:creates resources
     :updates 0
     :destroys 0}))

(defn ^{:stratum 1} analyze-terraform-plan-impl
  "Analyze Terraform plan artifact for resource changes.
   Returns {:creates N :updates N :destroys N}"
  [plan-artifact]
  (->> (artifact-content plan-artifact)
       str/split-lines
       (keep parse-terraform-change-line)
       resource-counts))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} analyze-artifact [artifact]
  (case (:artifact/type artifact)
    :terraform-plan (analyze-terraform-plan-impl artifact)
    :kubernetes-manifest (analyze-kubernetes-manifest-impl artifact)
    rules/empty-changes))

(comment
  (analyze-artifact {}))
