;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-inventory
  "Enumerate immutable records without silently dropping corrupt or vanished entries."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.publication :as publication]
            [ai.miniforge.artifact.publication-boundary :as boundary]
            [ai.miniforge.artifact.publication-files :as files]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-file? [^File file]
  (str/ends-with? (.getName file) files/record-suffix))

(defn- ^{:stratum 0} filename-id [^File file]
  (let [filename (.getName file)
        prefix (subs filename 0 (- (count filename) (count files/record-suffix)))
        id (parse-uuid prefix)]
    (when (= prefix (str id)) id)))

(defn- ^{:stratum 0} directory-entries [directory]
  (when (files/safe-directory? directory)
    (.listFiles (io/file directory))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} collect-record [directory records file]
  (let [id (filename-id file)
        record (when id (publication/read-record directory id))]
    (cond
      (anomaly/anomaly? record) (reduced record)
      (nil? record) (reduced (boundary/failure :fault :publication/read-failed id))
      :else (conj records record))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-records [directory]
  (if-let [entries (directory-entries directory)]
    (reduce (partial collect-record directory) [] (filter record-file? entries))
    (boundary/failure :fault :publication/read-failed nil)))

(comment
  ::immutable-inventory)
