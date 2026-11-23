;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.owned-resource
  "Transfer a native resource only with a successful owning result."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [slingshot.slingshot :refer [try+]])
  (:import [java.io Closeable]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} close-native! [^Closeable resource] (.close resource))

(defn ^{:stratum 0} call-with-release! [resource release! f]
  (let [retained? (volatile! false)]
    (try+
      (let [result (f resource)]
        (vreset! retained? (not (anomaly/anomaly? result)))
        result)
      (finally (when-not @retained? (release! resource))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call! [resource f]
  (call-with-release! resource close-native! f))

(comment
  (call-with-release! :probe identity identity))
