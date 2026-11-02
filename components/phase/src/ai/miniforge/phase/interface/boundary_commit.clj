;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase.interface.boundary-commit
  "How a phase-boundary commit names the phases whose work it holds: git
   trailers the workflow writes and release reads back.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} phase-trailer-key
  "Git trailer key on a phase-boundary commit. One trailer per phase whose
   work the commit holds, so a reader can tell which phases made it."
  "Miniforge-Phase")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-phase-trailers
  "`message` with a trailer naming each of `phases` as its last paragraph,
   where git's trailer parser finds them."
  [message phases]
  (str message "\n" (apply str (map #(str "\n" phase-trailer-key ": " (name %)) phases))))
