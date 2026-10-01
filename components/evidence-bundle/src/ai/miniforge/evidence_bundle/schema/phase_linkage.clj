;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.phase-linkage
  "Join phase audit ranges to their enclosing workflow event link."
  (:require [ai.miniforge.evidence-bundle.schema.domain :as domain]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} phase-fields
  [:evidence/plan :evidence/design :evidence/implement :evidence/verify
   :evidence/review :evidence/release :evidence/observe])

(defn- ^{:stratum 0} workflow-link? [workflow-id link]
  (and (= :workflow (:event-links/scope-type link))
       (= workflow-id (:event-links/scope-id link))))

(defn- ^{:stratum 0} covered-phase? [link phase]
  (let [range (:phase/event-stream-range phase)
        start (:event-links/from-sequence link)
        end (:event-links/to-sequence link)]
    (and (domain/event-stream-range? range)
         (nat-int? start) (nat-int? end)
         (<= start (:start-seq range) (:end-seq range) end))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} valid? [bundle]
  (let [phases (vals (select-keys bundle phase-fields))
        workflow-id (:evidence-bundle/workflow-id bundle)
        link (first (filter (partial workflow-link? workflow-id) (:evidence/event-links bundle)))]
    (or (empty? phases) (and (some? link) (every? (partial covered-phase? link) phases)))))

(comment
  (valid? {}))
