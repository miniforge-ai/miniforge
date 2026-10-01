;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.gate-waivers
  "Ordinary policy waivers never substitute for high-severity authorization."
  (:require [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} eligible? [violation]
  (contains? #{:medium :low :info} (:violation/severity violation)))

(defn- ^{:stratum 0} blocking? [violation]
  (contains? #{:critical :high} (:violation/severity violation)))

(defn- ^{:stratum 0} waiver-matches? [evaluation eligible waiver]
  (let [waived (:waiver/violations waiver)]
    (and (= evaluation (:waiver/evaluation-id waiver))
         (seq waived) (set/subset? (set waived) eligible))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} consistent? [record]
  (let [violations (:gate-execution/violations record)
        waivers (:gate-execution/waivers record)
        eligible (set (map :violation/rule-id (filter eligible? violations)))
        outcome (:gate-execution/outcome record)
        evaluation (:gate-execution/evaluation-id record)]
    (and (or (empty? waivers) (true? (:gate-execution/allow-override? record)))
         (every? (partial waiver-matches? evaluation eligible) waivers)
         (or (not= :waived outcome) (seq waivers))
         (not (and (= :passed outcome) (seq waivers)))
         (or (= :failed outcome) (not-any? blocking? violations)))))
