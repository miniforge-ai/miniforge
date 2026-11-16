;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-mountinfo
  "Select only an unambiguous, supported mount from the process mount table."
  (:require [babashka.fs :as fs]
            [clojure.string :as str])
  (:import [java.nio.file Path]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} mount-line
  #"([0-9]+) [0-9]+ ([0-9]+:[0-9]+) \S+ (\S+) (\S+)(?: \S+)*? - (\S+) \S+ (\S+)")

(def ^{:stratum 0 :private true} path-escapes
  "Kernel mount-table escapes are not general octal or shell interpolation."
  {"\\040" " " "\\011" "\t" "\\012" "\n" "\\134" "\\"})

(def ^{:stratum 0 :private true} supported-filesystems #{"ext4" "xfs"})

(def ^{:stratum 0 :private true} unsafe-options
  "Reject known modes that defeat writes, barriers, or normal journal replay."
  #{"ro" "nobarrier" "barrier=0" "noload" "norecovery"})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} mount [line]
  (when-let [[_ id device target options type super-options] (re-matches mount-line line)]
    {:mount/id id
     :mount/device device
     :mount/target (fs/path (str/replace target #"\\(?:040|011|012|134)" path-escapes))
     :mount/type type
     :mount/options (set (str/split (str options "," super-options) #","))}))

(defn- ^{:stratum 1} writable-local-type? [{:mount/keys [type options]}]
  (and (supported-filesystems type)
       (contains? options "rw")
       (not-any? unsafe-options options)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} matching-mount
  "Return the most specific mount only when its identity is unambiguous."
  [^Path root contents]
  (let [mounts (mapv mount (str/split-lines contents))
        candidates (->> mounts (keep :mount/target) (filter #(.startsWith root ^Path %)))
        closest (last (sort-by #(.getNameCount ^Path %) candidates))
        matches (filter #(= closest (:mount/target %)) mounts)]
    (when (and closest (not-any? nil? mounts) (= 1 (count matches)))
      (let [entry (first matches)]
        (when (writable-local-type? entry) entry)))))
