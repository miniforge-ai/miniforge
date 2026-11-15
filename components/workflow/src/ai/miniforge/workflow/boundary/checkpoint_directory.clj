;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-directory
  "Adapt protected storage failures to the checkpoint I/O boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.workflow.messages :as messages]
            [babashka.fs :as fs])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} populated? [directory]
  (boolean (and (fs/directory? directory) (seq (fs/list-dir directory)))))

(defn- ^{:stratum 0} checkpoint-path [directory result]
  (if (anomaly/anomaly? result)
    (throw (IOException. ^String (messages/system :checkpoint/unsafe-directory {:path (str directory)})))
    result))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} existing! [directory]
  (checkpoint-path directory (durability/resolve-protected-directory (fs/file directory))))

(defn ^{:stratum 1} prepare! [directory]
  (checkpoint-path directory (durability/prepare-protected-directory! (fs/file directory))))

(comment ::checkpoint-directory-boundary)
