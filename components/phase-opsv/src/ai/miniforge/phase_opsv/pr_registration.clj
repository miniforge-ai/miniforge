;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-registration
  "Account for grant writes before admission can close or cleanup can complete."
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.run-control :as control]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} track! [runtime now issued]
  (if-let [handle (:control runtime)]
    (control/track-grant! handle issued now)
    issued))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} register-tracked! [runtime issued now]
  (-> (track! runtime now issued)
      (flow/continue (partial grant/register! (:authority-directory runtime)))
      (flow/continue (partial track! runtime now))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} register! [runtime issued now]
  (if-let [handle (:control runtime)]
    (control/at-boundary! handle (partial register-tracked! runtime issued now))
    (register-tracked! runtime issued now)))

(comment
  (track! {} nil {}))
