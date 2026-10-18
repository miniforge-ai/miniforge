;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.journal-writer
  "Translate immutable artifact receipts into event acknowledgments."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.journal-record :as record]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} persist! [directory scope event]
  (let [value (record/wrap-event scope event)
        receipt (artifact/publish! directory value)]
    (cond
      (anomaly/anomaly? receipt) receipt
      (= value receipt) event
      :else (model/failure :fault :commit/unacknowledged event))))

(comment
  ::artifact-receipt)
