;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.current-view
  "Detach current event data for callers without redacting trusted journal positions."
  (:require [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} event [committed]
  (merge (redaction/redact (dissoc committed :event/sequence-number))
         (select-keys committed [:event/sequence-number])))

(comment
  ::detached-current-event)
