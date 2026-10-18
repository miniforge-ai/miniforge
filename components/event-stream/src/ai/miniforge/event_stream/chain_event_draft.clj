;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.chain-event-draft
  "Typed chain construction; lifecycle authority and publication remain separate."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.envelope-draft :as envelope]
            [ai.miniforge.event-stream.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create
  "Assemble an already-validated chain payload. Keep the draft for retries."
  [stream payload opts]
  (let [event-type (:event/type payload)
        message (messages/t event-type)
        draft (envelope/create stream event-type (:workflow/id payload) message opts)]
    (if (anomaly/any-anomaly? draft) draft (merge draft payload))))

(comment
  ::typed-chain-draft)
