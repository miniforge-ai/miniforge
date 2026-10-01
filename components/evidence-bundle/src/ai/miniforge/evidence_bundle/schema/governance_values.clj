;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.governance-values
  "Scalar contracts shared by portable knowledge and gate evidence."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} sha256 [:re #"\A[0-9a-fA-F]{64}\z"])

(def ^{:stratum 0} resolved-version
  [:re (re-pattern
        (str "\\A(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
             "(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
             "(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?"
             "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?\\z"))])

(defn ^{:stratum 0} nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn ^{:stratum 0} record-vector [item-schema]
  [:and vector? [:vector item-schema]])
