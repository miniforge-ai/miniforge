;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.mac-store-fixtures
  "Mirror diskutil/plutil metadata, including absent optional physical-classification data."
  (:require [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} backing-id
  "A physical partition distinct from the observed APFS volume."
  "disk0s1")

(def ^{:stratum 0} volume-id
  "The APFS volume selected by the native df fixture."
  "disk1s1")

(def ^{:stratum 0} ^:private mounted-volume
  "One header and exactly one selected APFS mount, matching native df output."
  "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/disk1s1 10 1 9 10% /")

(defn ^{:stratum 0} physical-store [identifier]
  {:APFSPhysicalStore identifier})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} backing [overrides]
  (merge {:DeviceIdentifier backing-id
          :Internal true
          :WritableMedia true
          :BusProtocol "Apple Fabric"
          :DeviceTreePath "IODeviceTree:/storage"
          :DiskUUID "82002d9d-8d3f-427a-bd4b-cf9bb11131a3"}
         overrides))

(defn ^{:stratum 1} volume [root overrides]
  (let [stores [(physical-store backing-id)]]
    (merge {:DeviceIdentifier volume-id
            :FilesystemName "APFS"
            :WritableVolume true
            :APFSSnapshot false
            :GlobalPermissionsEnabled true
            :MountPoint (str root)
            :APFSPhysicalStores stores}
           overrides)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-store [root volume-overrides backing-overrides arguments payload]
  (let [metadata (if (= backing-id payload) (backing backing-overrides) (volume root volume-overrides))
        encoded (json/generate-string metadata)
        identifier (last arguments)
        replies {"/bin/df" mounted-volume
                 "/usr/sbin/diskutil" identifier
                 "/usr/bin/plutil" encoded}]
    (get replies (first arguments))))

(comment (backing {}))
