;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-mountinfo-row
  "Decode kernel mount rows and apply the supported writable-filesystem policy."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private mount-line
  "Read mount identity and filesystem facts while accepting optional kernel fields."
  #"([0-9]+) [0-9]+ ([0-9]+:[0-9]+) \S+ (\S+) (\S+)(?: \S+)*? - (\S+) \S+ (\S+)")

(def ^{:stratum 0} ^:private path-escapes
  "Kernel mount-table escapes are not general octal or shell interpolation."
  {"\\040" " "
   "\\011" "\t"
   "\\012" "\n"
   "\\134" "\\"})

(def ^{:stratum 0} ^:private supported-filesystems
  "The first profile requires single-device XFS; ext4 needs separate journal evidence."
  #{"xfs"})

(def ^{:stratum 0} ^:private unsafe-options
  "Reject known modes that defeat writes, barriers, or normal journal replay."
  #{"ro" "nobarrier" "barrier=0" "noload" "norecovery"})

(def ^{:stratum 0} ^:private separate-device-options
  "A data-device observation cannot qualify an external journal or realtime device."
  #{"logdev" "rtdev"})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} unsafe-option? [option]
  (or (contains? unsafe-options option)
      (contains? separate-device-options (first (str/split option #"=" 2)))))

(defn- ^{:stratum 1} target-path [encoded]
  (fs/path (str/replace encoded #"\\(?:040|011|012|134)" path-escapes)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} writable-local-type? [{:mount/keys [type options]}]
  (and (supported-filesystems type)
       (contains? options "rw")
       (not-any? unsafe-option? options)))

(defn ^{:stratum 2} parse [line]
  (when-let [[_ id device target options type super-options] (re-matches mount-line line)]
    (let [path (target-path target)
          flags (set (str/split (str options "," super-options) #","))]
      {:mount/id id
       :mount/device device
       :mount/target path
       :mount/type type
       :mount/options flags})))

(comment (parse "36 35 98:0 / / rw - ext4 /dev/root rw"))
