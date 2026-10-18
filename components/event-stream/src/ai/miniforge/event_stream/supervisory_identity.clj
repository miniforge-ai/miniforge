;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.supervisory-identity
  "Equality constraints between supervisory payload identities and scope keys.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} intervention-key? [event]
  (= (:supervisory/entity-key event) (:intervention/id event)))

(defn ^{:stratum 0} spec-key? [event]
  (= (:supervisory/entity-key event) (get-in event [:supervisory/entity :spec/id])))

(comment
  ::supervisory-identity)
