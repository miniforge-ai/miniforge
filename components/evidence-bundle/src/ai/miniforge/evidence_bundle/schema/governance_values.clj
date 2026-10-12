;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.governance-values
  "Scalar contracts shared by portable knowledge and gate evidence."
  (:require [clojure.string :as str]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} sha256 [:re #"\A[0-9a-fA-F]{64}\z"])

(def ^{:stratum 0} resolved-version
  schema/SemanticVersion)

(defn ^{:stratum 0} nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn ^{:stratum 0} record-vector [item-schema]
  [:and vector? [:vector item-schema]])

(comment
  resolved-version)
