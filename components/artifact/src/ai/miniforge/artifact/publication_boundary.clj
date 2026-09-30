;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-boundary
  "Convert filesystem and codec failures at the artifact publication boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.messages :as msg])
  (:import [java.io ByteArrayOutputStream IOException OutputStream]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} bounded-output [^ByteArrayOutputStream buffer limit]
  ;; Java's void OutputStream callback cannot return an anomaly. Abort the codec
  ;; here; the publication boundary converts its IOException into invalid-input.
  (proxy [OutputStream] []
    (write
      ([value]
       (if (< (.size buffer) limit)
         (.write buffer (int value))
         (throw (IOException. (msg/t :publication/not-portable)))))
      ([bytes offset length]
       (if (<= (+ (.size buffer) length) limit)
         (.write buffer bytes offset length)
         (throw (IOException. (msg/t :publication/not-portable))))))))

(defn ^{:stratum 0} failure [type key id]
  (anomaly/anomaly type (msg/t key) {:artifact/id id}))

(defn ^{:stratum 0} safe-path-with-exception-handling [predicate directory]
  (try (boolean (predicate directory))
       (catch IllegalArgumentException _ false)
       (catch java.io.IOException _ false)))

(defn- ^{:stratum 0} publish-and-confirm! [publish confirm]
  (publish)
  (confirm))

(defn- ^{:stratum 0} retain-cleanup-failure [result cleanup]
  (if (anomaly/anomaly? result)
    (assoc-in result [:anomaly/data :publication/cleanup-failure] cleanup)
    (assoc-in cleanup [:anomaly/data :publication/confirmed-artifact] result)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-exception-handling
  ([id operation] (call-with-exception-handling id :unavailable :publication/unconfirmed operation))
  ([id type key operation]
   (try (operation)
        (catch InterruptedException _
          (let [result (failure type key id)] (.interrupt (Thread/currentThread)) result))
        (catch Error _ (failure :fatal :publication/fatal id))
        (catch Throwable _ (failure type key id)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish-with-cleanup [id publish confirm cleanup]
  (let [result (call-with-exception-handling id (partial publish-and-confirm! publish confirm))
        cleaned (call-with-exception-handling id :unavailable :publication/cleanup-failed cleanup)]
    (if (anomaly/anomaly? cleaned) (retain-cleanup-failure result cleaned) result)))
