;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-execution-fixtures
  (:require [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures :as forward]
            [ai.miniforge.phase-opsv.kubernetes-load-absence-fixtures :as owned]
            [ai.miniforge.phase-opsv.kubernetes-load-start-execution-wire-fixtures :as transport]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} execute transport/execute)

(def ^{:stratum 0} execute-response transport/execute-response)

(defn ^{:stratum 0} mutations [calls] (filterv #(= :patch (:method %)) calls))

(def ^{:stratum 0} dispositions forward/dispositions)

(def ^{:stratum 0} assembly forward/assembly)

(defn ^{:stratum 0} pod-reads [fixture calls]
  (count (filter #(and (= :get (:method %)) (= (owned/pod-path fixture) (wire/path %))) calls)))

(defn- ^{:stratum 0} configured [f {:keys [native pod] :as fixture}]
  (let [routes (assoc (:routes native) (owned/pod-path fixture) pod)]
    (forward/with-runtime #(f (assoc % :host/routes routes)) fixture)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-startable [f]
  (owned/with-created (partial configured f)))

(comment
  (with-startable #(count (:host/routes %))))
