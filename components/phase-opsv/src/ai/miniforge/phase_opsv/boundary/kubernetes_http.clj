;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-http
  "Read Kubernetes JSON with owned HTTP futures and one shared capture deadline."
  (:require [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeoutException]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} request-options [settings path params]
  (let [base (str/replace (:kubernetes/base-url settings) #"/+$" "")
        uri (str base path)]
    (http/json-get-options uri (:kubernetes/bearer-token settings)
                           (:kubernetes/timeout-ms settings) params)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-resource! [settings until path params]
  (when-not (pos? (io/remaining-ms until)) (throw (TimeoutException.)))
  (let [pending (http/start! (request-options settings path params))
        operation (partial response/observe! settings until pending)
        result (io/owned-result! operation (partial http/release! pending))]
    (when-not (pos? (io/remaining-ms until)) (throw (TimeoutException.)))
    result))

(comment ::bounded-read-only-kubernetes-api)
