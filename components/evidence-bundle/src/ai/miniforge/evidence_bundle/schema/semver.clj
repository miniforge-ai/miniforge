;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.semver
  "SemVer precedence for recorded policy-pack versions, not workspace DateVer."
  (:require [clojure.string :as str]
            [ai.miniforge.evidence-bundle.schema.governance-values :as values]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} identifier [value]
  (if (re-matches #"[0-9]+" value) [0 (bigint value)] [1 value]))

(defn- ^{:stratum 0} compare-identifiers [left right]
  (loop [a left b right]
    (cond
      (and (empty? a) (empty? b)) 0
      (empty? a) -1
      (empty? b) 1
      (= (first a) (first b)) (recur (rest a) (rest b))
      :else (compare (first a) (first b)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} parse [value]
  (when-let [[_ major minor patch prerelease] (and (string? value) (re-matches (second values/resolved-version) value))]
    (let [core (mapv bigint [major minor patch])
          pre (some->> prerelease (#(str/split % #"\.")) (mapv identifier))]
      {:core core :pre pre})))

(defn ^{:stratum 1} precedence [left right]
  (let [core-order (compare (:core left) (:core right))]
    (cond
      (not (zero? core-order)) core-order
      (= (:pre left) (:pre right)) 0
      (nil? (:pre left)) 1
      (nil? (:pre right)) -1
      :else (compare-identifiers (:pre left) (:pre right)))))
