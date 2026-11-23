;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-device-ancestry
  "Read kernel driver bindings and transport facts from every device ancestor."
  (:require [ai.miniforge.file-durability.boundary.linux-device-policy :as policy]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} driver-binding [directory]
  (let [link (fs/path directory "driver")]
    (cond
      (fs/sym-link? link) (str (fs/real-path link))
      (fs/exists? link) ::unknown
      :else nil)))

(defn- ^{:stratum 0} transport [directory]
  (let [file (fs/path directory "transport")]
    (cond
      (not (fs/exists? file)) nil
      (fs/regular-file? file) (str/trim (slurp file))
      :else ::unknown)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} device-facts [directory]
  (let [driver (driver-binding directory)
        transport (transport directory)]
    {:device/driver driver
     :device/transport transport}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe [path]
  (->> (iterate fs/parent path)
       (take-while #(and % (fs/starts-with? % policy/device-tree)))
       (mapv device-facts)))

(comment (observe (fs/path "/outside-sysfs")))
