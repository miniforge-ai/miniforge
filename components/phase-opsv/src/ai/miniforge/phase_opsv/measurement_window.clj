;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.measurement-window
  "Preserve the declared query and measurement grid without guessing aggregates."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private window-keys
  [:window/start-seconds :window/end-seconds :window/step-seconds])

(defn- ^{:stratum 0} sample-count [request]
  (let [{:window/keys [start-seconds end-seconds step-seconds]} request
        intervals (quot (- end-seconds start-seconds) step-seconds)]
    (inc' intervals)))

(defn- ^{:stratum 0} expected-times [request]
  (let [{:window/keys [start-seconds end-seconds step-seconds]} request]
    (range start-seconds (inc' end-seconds) step-seconds)))

(defn- ^{:stratum 0} sample [[time value]] [time (Double/parseDouble value)])

(defn- ^{:stratum 0} finite-sample? [[time value]]
  (and (Double/isFinite (double time)) (Double/isFinite value)))

(defn- ^{:stratum 0} same-time? [expected observed]
  (= (bigdec expected) (bigdec observed)))

(defn ^{:stratum 0} invalid-response []
  (anomaly/anomaly :invalid-input (msg/ts :measurement/invalid-response) {}))

(defn ^{:stratum 0} within-cap? [maximum request]
  (<= (:measurement/max-samples request) maximum))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} measurement [request series samples]
  (let [window (select-keys request window-keys)]
    {:prometheus/query (:prometheus/query request)
     :measurement/window window
     :measurement/labels (get series "metric")
     :measurement/samples samples}))

(defn ^{:stratum 1} bounded? [request]
  (and (< (:window/start-seconds request) (:window/end-seconds request))
       (<= 2 (sample-count request) (:measurement/max-samples request))))

(defn- ^{:stratum 1} complete? [request samples]
  (and (= (sample-count request) (count samples))
       (every? finite-sample? samples)
       (every? true? (map same-time? (expected-times request) (map first samples)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} decode [request response]
  (let [series (first (get-in response ["data" "result"]))
        samples (mapv sample (get series "values"))]
    (if (complete? request samples)
      (measurement request series samples)
      (invalid-response))))

(comment ::declared-measurement-window)
