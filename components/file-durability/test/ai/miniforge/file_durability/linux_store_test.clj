;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.linux-store-test
  (:require [ai.miniforge.file-durability.boundary.linux-store :as store]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private mount-table
  "An otherwise eligible root mount whose device must also pass native checks."
  "36 35 98:0 / / rw - ext4 /dev/root rw")

(def ^{:stratum 0} ^:private block-paths
  "Only a non-virtual path in the kernel device tree can pass."
  [["/sys/devices/platform/block/disk" true]
   ["/sys/devices/virtual/block/disk" false]
   ["/other/block/disk" false]])

(defn- ^{:stratum 0} device-attributes [same-device? path & _]
  (let [device (if (or same-device? (= (fs/path "/workspace") path)) 1 2)]
    {:dev device
     :ino 1}))

(defn- ^{:stratum 0} refuse-resolution! [& _] (throw (AssertionError.)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-observation-requires-physical-backing-on-the-roots-device
  (doseq [[backing physical?] block-paths
          same-device? [true false]]
    (with-redefs [clojure.core/slurp (constantly mount-table)
                  fs/sym-link? (constantly true)
                  fs/real-path (constantly (fs/path backing))
                  fs/read-attributes (partial device-attributes same-device?)]
      (is (= (and physical? same-device?)
             (some? (store/observe (fs/path "/workspace"))))))))

(deftest ^{:stratum 1} test-unmapped-device-does-not-resolve-or-admit-a-backing
  (with-redefs [clojure.core/slurp (constantly mount-table)
                fs/sym-link? (constantly false)
                fs/real-path refuse-resolution!]
    (is (nil? (store/observe (fs/path "/workspace"))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.linux-store-test))
