;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.linux-store-test
  (:require [ai.miniforge.file-durability.boundary.linux-store :as store]
            [ai.miniforge.file-durability.linux-storage-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} observe-root [] (store/observe (fs/path "/workspace")))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-observation-requires-a-recognized-local-controller-stack
  (doseq [topology [fixtures/ahci fixtures/nvme fixtures/virtio-scsi
                    ["/sys/devices/platform/block/disk" {} {} "8:0" 2048 false]
                    ["/sys/devices/virtual/block/disk" {} {} "8:0" 2048 false]
                    ["/other/block/disk" {} {} "8:0" 2048 false]]
          same-device? [true false]
          :let [fixture (fixtures/kernel topology same-device?)
                observed (fixtures/with-kernel fixture observe-root)]]
    (is (= (and (last topology) same-device?) (some? observed)))))

(deftest ^{:stratum 1} test-unmapped-device-does-not-resolve-or-admit-a-backing
  (let [fixture (update (fixtures/kernel fixtures/ahci true) :fixture/links dissoc fixtures/device-link)]
    (is (nil? (fixtures/with-kernel fixture observe-root)))))

(deftest ^{:stratum 1} test-nvme-network-loop-and-missing-transports-refuse
  (doseq [transport ["tcp\n" "rdma\n" "fc\n" "loop\n" nil]
          :let [topology (fixtures/nvme-transport transport)
                fixture (fixtures/kernel topology true)]]
    (is (nil? (fixtures/with-kernel fixture observe-root)))))

(deftest ^{:stratum 1} test-unknown-ancestor-driver-refuses-even-with-a-local-controller
  (let [topology (update fixtures/ahci 1 assoc "/sys/devices/pci0000:00/driver" "/sys/bus/pci/drivers/unknown")
        fixture (fixtures/kernel topology true)]
    (is (nil? (fixtures/with-kernel fixture observe-root)))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.linux-store-test))
