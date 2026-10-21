;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.throwable-graph
  "Identity-based reachability for exception suppression safety.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} children [failure]
  (cons (.getCause ^Throwable failure) (.getSuppressed ^Throwable failure)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} reaches? [root target]
  (loop [pending (list root) seen []]
    (let [current (first pending)]
      (cond
        (empty? pending) false
        (identical? current target) true
        (or (nil? current) (some #(identical? current %) seen))
        (recur (rest pending) seen)
        :else (recur (concat (children current) (rest pending)) (conj seen current))))))

(comment ::suppression-safety)
