;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-publication
  "Create-only grant publication with explicit durability failure propagation."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.store-path :as path]
            [ai.miniforge.file-durability.interface :as durability]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file FileAlreadyExistsException Files]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temporary ^File [^File target]
  (io/file (.getParentFile target) (str (.getName target) "." (random-uuid) ".tmp")))

(defn ^{:stratum 0} confirm! [file record on-error]
  (let [confirmed (durability/confirm! file)]
    (if (anomaly/anomaly? confirmed) (on-error) record)))

(defn- ^{:stratum 0} link-record! [^File target ^File tmp record on-error]
  (Files/createLink (.toPath target) (.toPath tmp))
  (let [confirmed (durability/sync-ancestry! (.getParentFile target))]
    (if (anomaly/anomaly? confirmed) (on-error) record)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} write-record! [target tmp encoded record on-error]
  (io/make-parents target)
  (let [written (durability/write-new-text! tmp encoded)]
    (if (anomaly/anomaly? written) (on-error)
      (link-record! target tmp record on-error))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish-with-exception-handling! [target encoded record on-error on-conflict]
  (let [tmp (temporary target)]
    (try
      (if (path/safe? target)
        (write-record! target tmp encoded record on-error)
        (on-error))
      (catch FileAlreadyExistsException _ (on-conflict))
      (catch Exception _ (on-error))
      (finally (.delete tmp)))))

(comment
  ::create-only)
