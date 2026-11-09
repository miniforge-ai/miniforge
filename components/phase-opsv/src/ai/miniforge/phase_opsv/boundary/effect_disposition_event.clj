;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.effect-disposition-event
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.effect-disposition :as disposition]
            [ai.miniforge.phase-opsv.event-admission :as admission]
            [ai.miniforge.phase-opsv.event-replay :as replay]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unidentified-draft [ctx stream bundle-id data]
  (let [workflow-id (context/workflow-id ctx)
        expected (assoc data :event/type :opsv.actuation/disposition
                             :workflow/id workflow-id :opsv/evidence-bundle-id bundle-id)
        published (if (events/current-event-stream? stream) [] (events/get-events stream))]
    (if (anomaly/any-anomaly? published) published
      (or (disposition/matching-event expected (concat published (:opsv/disposition-events ctx)))
          (events/actuation-disposition stream workflow-id bundle-id data)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} draft [ctx stream bundle-id data]
  ;; Retained pending evidence must carry the identity publication will actually acknowledge.
  (anomaly/let-ok [prepared (admission/prepare stream (unidentified-draft ctx stream bundle-id data))]
    (replay/identify prepared)))

(comment
  (disposition/matching-event {:event/type :opsv.actuation/disposition} []))
