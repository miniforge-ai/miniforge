;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.mac-store-test
  (:require [ai.miniforge.file-durability.boundary.mac-store :as store]
            [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [ai.miniforge.file-durability.boundary.root-identity :as identity]
            [ai.miniforge.file-durability.mac-store-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} unsupported-volumes
  "Each changed provider fact independently prevents APFS admission."
  [{:DeviceIdentifier "another-volume"}
   {:FilesystemName "unsupported"}
   {:WritableVolume false}
   {:APFSSnapshot true}
   {:APFSSnapshot nil}
   {:GlobalPermissionsEnabled false}
   {:GlobalPermissionsEnabled nil}
   {:MountPoint "relative"}
   {:APFSPhysicalStores []}])

(def ^{:stratum 0 :private true} unsupported-backings
  "Require internal writable Apple Fabric topology and a stable device identity."
  [{:DeviceIdentifier "another-device"}
   {:Internal false}
   {:WritableMedia false}
   {:BusProtocol "USB"}
   {:VirtualOrPhysical "Virtual"}
   {:DeviceTreePath nil}
   {:DiskUUID "invalid"}
   {:DiskUUID nil}
   {:DiskUUID 1}])

(defn- ^{:stratum 0} verify-observation! [root expected volume backing]
  (with-redefs [probe/read-output (partial fixtures/read-store root volume backing)]
    (is (= expected (some? (store/observe root))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-provider-facts! []
  (let [root (fs/path "/staging")]
    (with-redefs [fs/real-path fs/path identity/same-device? (constantly true)]
      (verify-observation! root true {} {})
      (doseq [volume unsupported-volumes] (verify-observation! root false volume {}))
      (doseq [backing unsupported-backings] (verify-observation! root false {} backing))
      (with-redefs [identity/same-device? (constantly false)]
        (verify-observation! root false {} {})))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-apfs-observations-require-every-provider-fact
  (verify-provider-facts!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.mac-store-test))
