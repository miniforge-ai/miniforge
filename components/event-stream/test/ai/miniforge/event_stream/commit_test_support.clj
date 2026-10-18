;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.commit-test-support)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} draft []
  {:event/id (random-uuid)
   :event/type :workflow/started
   :event/timestamp (java.util.Date.)
   :event/version "1.0.0"
   :message "started"})

(defn ^{:stratum 0} record! [receipts scope event]
  (swap! receipts conj [scope event])
  event)

(comment
  (draft))
