;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-software-factory.verify.failure-block
  "Read one bounded clojure.test failure block."
  (:require [ai.miniforge.phase-software-factory.verify.output-config :as config]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure-detail-line?
  "A line that still belongs to the failure block above it: non-blank and
   not the next failure header."
  [line]
  (and (not (str/blank? line))
       (nil? (re-find config/failure-header-pattern line))))

(defn ^{:stratum 0} failure-header-index
  "Index of `line` when it is a failure header, else nil (for keep-indexed)."
  [index line]
  (when (re-find config/failure-header-pattern line)
    index))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} detail-lines [lines]
  (->> (rest lines)
       (take-while failure-detail-line?)
       (take config/max-failure-block-lines)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} failure-block [lines]
  (let [[_ tag test-name location] (re-find config/failure-header-pattern (first lines))
        kind (if (= "FAIL" tag) :fail :error)
        detail (str/join "\n" (detail-lines lines))]
    {:kind kind :test test-name :location location :detail detail}))

(comment ::failure-blocks)
