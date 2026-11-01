;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.prometheus-http
  "Raw HTTP acquisition keeps cancellation attached to the original JDK future."
  (:require [babashka.http-client :as http]
            [clojure.string :as str])
  (:import [java.net.http HttpResponse]
           [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private client
  (delay (http/client {:follow-redirects :never})))

(defn- ^{:stratum 0} query-parameters [settings request]
  (let [timeout (str (:prometheus/timeout-ms settings) "ms")]
    {:query (:prometheus/query request)
     :start (:window/start-seconds request)
     :end (:window/end-seconds request)
     :step (:window/step-seconds request)
     :timeout timeout
     :limit 2}))

(defn ^{:stratum 0} response-fields [^HttpResponse response]
  (let [encodings (vec (.allValues (.headers response) "content-encoding"))]
    {:status (.statusCode response)
     :body (.body response)
     :encodings encodings}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} request-options [settings request]
  (let [base (str/replace (:prometheus/base-url settings) #"/+$" "")
        uri (str base "/api/v1/query_range")
        params (query-parameters settings request)]
    {:uri uri
     :method :get
     :headers {"Accept" "application/json"
               "Accept-Encoding" "identity"}
     :oauth-token (:prometheus/bearer-token settings)
     :query-params params
     :timeout (:prometheus/timeout-ms settings)
     :async true
     :raw true}))

(defn ^{:stratum 1} release! [^CompletableFuture pending]
  (.cancel pending true)
  ;; A deadline can race a completed response. Close it even when await lost
  ;; that race; successful cancellation leaves disposal to the HTTP client.
  (when-not (.isCompletedExceptionally pending)
    (some-> (.getNow pending nil) response-fields :body .close)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start! [settings request]
  (http/request (assoc (request-options settings request) :client @client)))

(comment ::read-only-prometheus-http)
