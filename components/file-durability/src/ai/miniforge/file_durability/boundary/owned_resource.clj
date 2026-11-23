;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.owned-resource
  "Transfer a native resource only with a successful owning result."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.resource-cleanup.interface :as cleanup])
  (:import [java.io Closeable]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} close-native! [^Closeable resource] (.close resource))

(defn- ^{:stratum 0} accept! [retained? result resource f]
  (let [value (f resource)]
    (vreset! retained? (not (anomaly/anomaly? value)))
    (vreset! result value)))

(defn- ^{:stratum 0} release-unretained! [retained? resource release!]
  (when-not @retained? (release! resource)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-release! [resource release! f]
  (let [retained? (volatile! false)
        result (volatile! nil)]
    (cleanup/finish! (partial accept! retained? result resource f)
                     (partial release-unretained! retained? resource release!))
    @result))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} call! [resource f]
  (call-with-release! resource close-native! f))

(comment
  (call-with-release! :probe identity identity))
