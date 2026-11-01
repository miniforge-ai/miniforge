;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-test-support
  (:require [ai.miniforge.phase-opsv.boundary.prometheus-http :as http]
            [ai.miniforge.phase-opsv.measurement-test-support :as measurement]
            [cheshire.core :as json])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} settings
  ([] (settings {}))
  ([overrides] (merge {:prometheus/base-url "https://metrics.invalid/prometheus/"} overrides)))

(defn ^{:stratum 0} body [text]
  (ByteArrayInputStream. (.getBytes ^String text StandardCharsets/UTF_8)))

(defn ^{:stratum 0} successful-text [] (json/generate-string (measurement/response)))

(defn ^{:stratum 0} completed [response] (CompletableFuture/completedFuture response))

(defn ^{:stratum 0} record-request! [calls pending settings request]
  (swap! calls conj [settings request])
  pending)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} response
  ([text] (response text {}))
  ([text overrides] (merge {:status 200
                           :body (body text)
                           :encodings []} overrides)))

(defn ^{:stratum 1} with-response [pending calls operation]
  ;; BB cannot reify java.net.http.HttpResponse. Only its external projection
  ;; is substituted here; the separate JVM fixture exercises the real type.
  (with-redefs [http/start! (partial record-request! calls pending)
                http/response-fields identity]
    (operation)))

(comment ::deterministic-prometheus-wire-fixtures)
