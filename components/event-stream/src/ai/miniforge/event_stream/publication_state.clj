;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.publication-state
  "Pure transitions for a stream's committed log and ordered delivery queue.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} initial [store]
  {:store store
   :pending clojure.lang.PersistentQueue/EMPTY
   :seen #{}
   :draining? false
   :closed? false})

(defn ^{:stratum 0} seen? [snapshot event]
  (contains? (get-in snapshot [:publication :seen]) (:event/id event)))

(defn ^{:stratum 0} admit [snapshot event]
  (-> snapshot
      (update :events conj event)
      (update-in [:publication :pending] conj event)
      (update-in [:publication :seen] conj (:event/id event))))

(defn ^{:stratum 0} pending [snapshot]
  (peek (get-in snapshot [:publication :pending])))

(defn ^{:stratum 0} delivered [snapshot]
  (update-in snapshot [:publication :pending] pop))

(comment
  (initial nil))
