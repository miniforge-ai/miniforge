;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.root-identity
  (:require [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} read-identity [path]
  (let [{:keys [dev ino]} (fs/read-attributes path "unix:dev,ino" {:nofollow-links true})]
    (when (and (integer? dev) (integer? ino))
      {:storage/device-number dev
       :storage/inode ino})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} same-device? [first-path second-path]
  (let [device (:storage/device-number (read-identity first-path))]
    (and (some? device) (= device (:storage/device-number (read-identity second-path))))))

(comment (read-identity (fs/path ".")))
