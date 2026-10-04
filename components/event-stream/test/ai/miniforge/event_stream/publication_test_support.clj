;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.publication-test-support
  (:require [ai.miniforge.event-stream.publication-state :as state]
            [ai.miniforge.event-stream.publication-store :as store]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} stream
  ([] (stream (store/volatile-store)))
  ([store]
   (atom {:events []
          :publication (state/initial store)})))

(defn ^{:stratum 0} record! [observed event]
  (swap! observed conj (:event/sequence-number event)))

(defn ^{:stratum 0} observe-critical! [call]
  (try+
    (call)
    nil
    (catch Object caught [caught (Thread/interrupted)])
    (finally (Thread/interrupted))))

(comment
  (stream))
