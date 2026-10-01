;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.control-authorization-adapter
  "Localize pure authorization decisions and construct public anomaly responses."
  (:require [ai.miniforge.event-stream.control-authorization :as authorization]
            [ai.miniforge.event-stream.messages :as messages]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} authorize-action [roles action requester]
  (let [{:keys [authorized? category message-key context]} (authorization/decide roles action requester)
        message (messages/t message-key context)]
    (cond-> {:authorized? authorized? :reason message}
      (not authorized?) (assoc :anomaly (response/make-anomaly category message context)))))
