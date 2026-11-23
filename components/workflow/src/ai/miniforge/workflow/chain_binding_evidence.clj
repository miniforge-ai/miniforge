;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-binding-evidence
  "Redact reporting values with their original target and source naming context."
  (:require [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} named-value [value context]
  (let [named {context value}]
    (if (redaction/supported? named)
      (val (first (redaction/redact named)))
      (redaction/marker))))

(defn- ^{:stratum 0} contexts [target expression]
  (cond (vector? expression) (conj expression target)
        (keyword? expression) [target expression]
        :else [target]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} protect [record]
  (let [target (:binding/target-input record)
        expression (:binding/expression record)]
    (cond-> record
      (contains? record :binding/value)
      (update :binding/value #(reduce named-value % (contexts target expression)))
      (string? expression) (update :binding/expression named-value target))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} redact [record]
  (let [protected (protect record)]
    (if (redaction/supported? protected) (redaction/redact protected) (redaction/marker))))

(comment
  (redact {:binding/target-input "password" :binding/value "fixture"}))
