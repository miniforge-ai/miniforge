;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.version-constraint
  "Fail-closed exact, caret, tilde and comparator-set evidence constraints."
  (:require [clojure.string :as str]
            [ai.miniforge.evidence-bundle.schema.semver :as semver]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} comparison-orders {nil #{0} "=" #{0} ">" #{1} ">=" #{0 1}
                        "<" #{-1} "<=" #{-1 0}})

(defn- ^{:stratum 0} expand-partial [value]
  (if (re-matches #"(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*))?" value)
    (str/join "." (take 3 (concat (str/split value #"\.") (repeat "0"))))
    value))

(defn- ^{:stratum 0} upper-core [operator [major minor patch] precision]
  (cond
    (= precision 1) [(inc major) 0N 0N]
    (= operator "~") [major (inc minor) 0N]
    (pos? major) [(inc major) 0N 0N]
    (or (pos? minor) (= precision 2)) [major (inc minor) 0N]
    :else [major minor (inc patch)]))

(defn- ^{:stratum 0} prerelease-allowed? [candidate [_ target _]]
  (and (:pre target) (= (:core candidate) (:core target))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} parse-comparator [token]
  (let [[_ operator value] (re-matches #"(>=|<=|>|<|=|\^|~)?([^\s]+)" token)
        range? (contains? #{"^" "~"} operator)
        version (semver/parse (if range? (expand-partial value) value))
        precision (count (str/split (or value "") #"\."))]
    (when version
      [operator version (when range? (upper-core operator (:core version) precision))])))

(defn- ^{:stratum 1} matches? [candidate [operator target upper]]
  (let [order (semver/precedence candidate target)]
    (if upper
      (and (not (neg? order)) (neg? (compare (:core candidate) upper)))
      (contains? (get comparison-orders operator) (compare order 0)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} satisfied? [constraint version]
  (let [candidate (semver/parse version)
        comparators (when (string? constraint) (mapv parse-comparator (str/split constraint #"\s+")))]
    (boolean
     (and candidate (seq comparators) (every? some? comparators)
          (or (nil? (:pre candidate)) (some (partial prerelease-allowed? candidate) comparators))
          (every? (partial matches? candidate) comparators)))))
