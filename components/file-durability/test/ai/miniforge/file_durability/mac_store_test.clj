;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.mac-store-test
  (:require [ai.miniforge.file-durability.boundary.mac-store :as store]
            [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} backing
  {:DeviceIdentifier "disk0s1" :Internal true :WritableMedia true
   :BusProtocol "Apple Fabric" :DeviceTreePath "IODeviceTree:/storage"
   :DiskUUID "82002d9d-8d3f-427a-bd4b-cf9bb11131a3"})

(defn- ^{:stratum 0} volume [root backing-device ownership]
  (cond-> {:DeviceIdentifier "disk1s1" :FilesystemName "APFS"
           :WritableVolume true :APFSSnapshot false :MountPoint (str root)
           :APFSPhysicalStores [{:APFSPhysicalStore backing-device}]}
    (some? ownership) (assoc :GlobalPermissionsEnabled ownership)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-store [root ownership arguments payload]
  (case (first arguments)
    "/bin/df" "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/disk1s1 10 1 9 10% /"
    "/usr/sbin/diskutil" (last arguments)
    "/usr/bin/plutil" (let [device (:DeviceIdentifier backing)]
                       (json/generate-string (if (= device payload)
                                               backing (volume root device ownership))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} apfs-required-permission-enforcement-is-explicit
  (support/with-root
   (fn [directory]
     (when (support/posix? directory)
       (let [root (fs/real-path directory)]
         (doseq [ownership [true false nil]]
           (with-redefs [probe/read-output (partial read-store root ownership)]
             (is (= (true? ownership) (some? (store/observe root)))))))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.mac-store-test))
