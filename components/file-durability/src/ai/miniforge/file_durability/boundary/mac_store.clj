;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.mac-store
  "Check the mounted APFS volume and its internal Apple Fabric backing device."
  (:require [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} df-apfs
  "The native df selects APFS by type and reports from the path's mount."
  ["/bin/df" "-P" "-T" "apfs"])

(def ^{:stratum 0 :private true} disk-info
  "Obtain volume and physical-store facts without changing either device."
  ["/usr/sbin/diskutil" "info" "-plist"])

(def ^{:stratum 0 :private true} property-list-to-json
  "Use the OS property-list parser for structured disk metadata."
  ["/usr/bin/plutil" "-convert" "json" "-o" "-" "-"])

(def ^{:stratum 0 :private true} disk-name
  "Disk metadata can nominate only a device identifier, never a path or flag."
  #"disk[0-9]+(?:s[0-9]+)*")

(defn- ^{:stratum 0} physical? [identifier info]
  (let [tree (:DeviceTreePath info)]
    (and (= identifier (:DeviceIdentifier info))
         (true? (:Internal info))
         (true? (:WritableMedia info))
         (= "Apple Fabric" (:BusProtocol info))
         (string? tree)
         (str/starts-with? tree "IODeviceTree:/")
         (not= "Virtual" (:VirtualOrPhysical info))
         (uuid? (parse-uuid (:DiskUUID info ""))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} volume-id [root]
  (let [rows (some-> (probe/read-output (conj df-apfs (str root)) nil) str/split-lines)
        disk (some-> rows second (str/split #"\s+" 2) first)]
    (when (and (= 2 (count rows)) (string? disk) (str/starts-with? disk "/dev/"))
      (re-matches disk-name (subs disk (count "/dev/"))))))

(defn- ^{:stratum 1} info [identifier]
  (some->> (probe/read-output (conj disk-info identifier) nil)
           (probe/read-output property-list-to-json)
           (#(json/parse-string-strict % true))))

(defn- ^{:stratum 1} backing-id [root identifier volume]
  (let [backings (:APFSPhysicalStores volume)
        store (:APFSPhysicalStore (first backings))
        mount (:MountPoint volume)]
    (when (and (= identifier (:DeviceIdentifier volume))
               (= "APFS" (:FilesystemName volume))
               (true? (:WritableVolume volume))
               (false? (:APFSSnapshot volume))
               (string? mount) (fs/absolute? mount)
               (identity/same-device? root (fs/real-path mount))
               (= 1 (count backings))
               (string? store))
      (re-matches disk-name store))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe [root]
  (let [volume (volume-id root)
        metadata (when volume (info volume))
        backing (when metadata (backing-id root volume metadata))
        device (when backing (info backing))]
    (when (and device (physical? backing device))
      {:storage/backend :macos-apfs-internal
       :storage/volume volume
       :storage/mount (:MountPoint metadata)
       :storage/device-id (:DiskUUID device)})))
