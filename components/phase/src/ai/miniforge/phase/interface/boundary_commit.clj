;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase.interface.boundary-commit
  "How a phase-boundary commit names the phase whose work it holds: a git
   trailer the workflow writes and release reads back.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} phase-trailer-key
  "Git trailer key on a phase-boundary commit. Its value is the name of the
   phase that ran, so a reader can tell which phase made each commit."
  "Miniforge-Phase")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-phase-trailer
  "`message` with the trailer naming `phase` as its last paragraph, where
   git's trailer parser finds it."
  [message phase]
  (str message "\n\n" phase-trailer-key ": " (name phase)))
