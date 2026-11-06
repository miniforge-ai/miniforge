;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-replica-http
  "One bounded conditional PATCH; uncertain responses never trigger a retry."
  (:require [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-http :as kubernetes]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [ai.miniforge.phase-opsv.kubernetes-replica-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-replica-outcome :as outcome]
            [cheshire.core :as json])
  (:import [java.util.concurrent TimeoutException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} bounded-settings [settings record]
  (let [duration (get-in record [:effect/proposal :opsv/experiment :opsv/duration-ms])]
    (update settings :kubernetes/timeout-ms min duration)))

(defn ^{:stratum 0} request-options [settings record]
  (let [payload (binding/request record)
        options (kubernetes/request-options settings (:path payload) nil)]
    (-> options
        (assoc :method :patch :body (json/generate-string (:body payload)))
        (assoc-in [:headers "Content-Type"] (:media-type payload)))))

(defn- ^{:stratum 0} submission-allowed? [remaining]
  (and (not (.isInterrupted (Thread/currentThread))) (pos? remaining)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} submit! [settings record until options]
  (let [remaining (io/remaining-ms until)]
    (if-not (submission-allowed? remaining)
      (outcome/refused)
      (let [pending (http/start! (assoc options :timeout (min remaining (:timeout options))))
            operation (partial response/observe! settings until pending)
            deployment (io/owned-result! operation (partial http/release! pending))]
        (when-not (pos? (io/remaining-ms until)) (throw (TimeoutException.)))
        (outcome/report record deployment)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} patch! [settings record]
  (let [bounded (bounded-settings settings record)
        until (io/deadline (:kubernetes/timeout-ms bounded))
        options (request-options bounded record)]
    (submit! bounded record until options)))

(comment ::shared-owned-future-deadline-byte-limit-strict-json-and-no-redirect-client)
