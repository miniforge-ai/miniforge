;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.owned-resource-fixtures
  (:require [ai.miniforge.file-durability.publication-support :as support])
  (:import [java.io IOException]
           [java.nio.channels FileChannel]
           [java.nio.file Files OpenOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} with-resource [f]
  (let [path (Files/createTempFile "miniforge-owned-resource-" "" (make-array FileAttribute 0))]
    (try
      (with-open [resource (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ]))]
        (f resource))
      (finally (Files/deleteIfExists path)))))

(defn ^{:stratum 0} reject-io! [& _] (throw (IOException.)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} with-resource-at [f root]
  (with-resource (partial f root)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-root-and-resource [f]
  (support/with-root (partial with-resource-at f)))

(comment (with-resource #(.isOpen ^FileChannel %)))
