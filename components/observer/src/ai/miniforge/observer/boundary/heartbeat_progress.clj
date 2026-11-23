;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.boundary.heartbeat-progress
  "Version-aware observer projection; unavailable progress is never healthy progress.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unavailable [reason]
  {:progress/status :unavailable :progress/reason reason})

(defn- ^{:stratum 0} available [semantics gap]
  {:progress/status :available :progress/semantics semantics :progress/gap-ms gap})

(defn- ^{:stratum 0} gap? [value] (and (int? value) (<= 0 value)))

(defn- ^{:stratum 0} version-family [version]
  (cond
    (nil? version) "1"
    (string? version) (second (re-matches #"([0-9]+)\.[0-9]+\.[0-9]+(?:[-+].+)?" version))
    :else :unsupported))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} current-progress [event]
  (let [status (:phase/progress-status event)
        gap (:phase/progress-gap-ms event)]
    (cond
      (= :unknown status) (unavailable :unknown-coverage)
      (and (contains? #{:none :observed} status) (gap? gap))
      (assoc (available :substantive gap) :progress/coverage status)
      :else (unavailable :invalid-progress))))

(defn- ^{:stratum 1} legacy-progress [event]
  (let [gap (:phase/gap-since-last-event-ms event)]
    (if (gap? gap) (available :legacy-liveness gap)
        (unavailable :legacy-gap-absent))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} interpret [event]
  (if (= :workflow/phase-heartbeat (:event/type event))
    (case (version-family (:event/version event))
      "1" (legacy-progress event)
      "2" (current-progress event)
      (unavailable :unsupported-version))
    (unavailable :not-heartbeat)))

(comment ::honest-progress-availability)
