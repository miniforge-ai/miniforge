;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-criteria-integration-fixtures
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as transport]
            [ai.miniforge.phase-opsv.boundary.prometheus-http :as http]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.prometheus-criteria-fixtures :as criteria]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reply! [responses calls settings request]
  (let [recorded (swap! calls conj [settings request])
        result (nth responses (dec (count recorded)))]
    (if (instance? Throwable result)
      (wire/failed-future result)
      (wire/completed (wire/response (json/generate-string result))))))

(defn ^{:stratum 0} responses []
  (let [count (count (:measurement/criteria (criteria/configuration)))]
    (vec (repeat count (criteria/response)))))

(defn ^{:stratum 0} unbound [response field]
  (assoc-in response ["data" "result" 0 "metric" field] "other"))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe! [configuration responses]
  (let [calls (atom [])]
    (with-redefs [http/start! (partial reply! responses calls)
                  transport/response-fields identity]
      [(phase/observe-prometheus-criteria! configuration) @calls])))

(comment ::real-client-with-injected-http-responses-only)
