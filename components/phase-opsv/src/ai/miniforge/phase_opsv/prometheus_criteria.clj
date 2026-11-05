;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-criteria
  (:require [ai.miniforge.phase-opsv.kubernetes-signal-model :as labels]
            [ai.miniforge.phase-opsv.measurement-window :as window]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} bounded-criterion? [criterion]
  (window/bounded? (:measurement/request criterion)))

(defn ^{:stratum 0} unique-criteria? [configuration]
  (let [identifiers (mapv :criterion/id (:measurement/criteria configuration))]
    (= (count identifiers) (count (set identifiers)))))

(defn ^{:stratum 0} within-endpoint-cap? [configuration settings]
  (let [maximum (:measurement/max-samples settings)
        requests (map :measurement/request (:measurement/criteria configuration))]
    (every? (partial window/within-cap? maximum) requests)))

(defn ^{:stratum 0} expected-labels [configuration]
  (let [target (:measurement/target configuration)
        service (:measurement/service configuration)
        names (:measurement/label-names configuration)]
    (labels/expected-labels (:cluster target) (:namespace target) service names)))

(defn- ^{:stratum 0} reduced-value [reduction observed]
  (let [values (mapv second (:measurement/samples observed))]
    (case reduction
      :maximum (apply max values)
      :minimum (apply min values)
      :last (peek values))))

(defn ^{:stratum 0} result [configuration records]
  (let [observations (into {} (map (juxt :criterion/id :criterion/observed) records))
        declared (select-keys configuration [:measurement/target :measurement/service :measurement/label-names])]
    {:observations observations
     :measurement/declaration declared
     :measurement/windows records}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record [criterion observed]
  (let [reduction (:measurement/reduction criterion)
        value (reduced-value reduction observed)]
    {:criterion/id (:criterion/id criterion)
     :measurement/request (:measurement/request criterion)
     :measurement/reduction reduction
     :measurement/observation observed
     :criterion/observed value}))

(defn ^{:stratum 1} bounded? [configuration]
  (every? bounded-criterion? (:measurement/criteria configuration)))

(comment ::declared-reductions-not-learned-success-criteria)
