;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.gate-consistency
  "Cross-record joins for structurally validated gate evidence."
  (:require [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} pack-name [id] (subs (str id) 1))

(defn- ^{:stratum 0} waiver-retained? [rule-ids waiver]
  (let [waived (:waiver/violations waiver)]
    (and (seq waived) (set/subset? (set waived) rule-ids))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} consistent? [record]
  (let [gate (:gate-execution/gate-id record)
        binding (:gate-execution/binding record)
        packs (set (map :pack/id (:gate-execution/packs record)))
        bound (set (map (comp pack-name :pack/id) (:binding/packs binding)))
        violations (:gate-execution/violations record)
        waivers (:gate-execution/waivers record)
        outcome (:gate-execution/outcome record)]
    (and (= gate (:gate/id binding))
         (set/subset? bound packs)
         (or (= :failed outcome) (seq bound))
         (every? #(and (= gate (:violation/gate-id %))
                        (contains? packs (pack-name (:violation/pack-id %)))) violations)
         (every? (partial waiver-retained? (set (map :violation/rule-id violations))) waivers)
         (or (not= :waived outcome) (seq waivers))
         (not (and (= :passed outcome) (seq waivers))))))
