;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.linux-storage-fixtures
  "Mirror kernel mount rows, driver symlinks and sysfs transport attributes."
  (:require [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} device-link
  "Bind mountinfo's exact device number to its sysfs target."
  "/sys/dev/block/8:0")

(def ^{:stratum 0} ^:private mounts
  "Only the process-local kernel mount table is substituted."
  "/proc/self/mountinfo")

(defn- ^{:stratum 0} mount-row [device]
  (str "36 35 " device " / / rw - xfs /dev/root rw"))

(def ^{:stratum 0} ^:private transport-path
  "The namespace's controller supplies the authoritative NVMe transport attribute."
  "/sys/devices/pci0000:00/0000:01:00.0/nvme/nvme0/transport")

(def ^{:stratum 0} ahci
  "A local ATA disk with a positively identified PCI AHCI controller."
  ["/sys/devices/pci0000:00/0000:00:17.0/ata1/host0/target0:0:0/0:0:0:0/block/sda"
   {"/sys/devices/pci0000:00/0000:00:17.0/driver" "/sys/bus/pci/drivers/ahci"} {} "8:0" 2048 true])

(def ^{:stratum 0} virtio-scsi
  "Oracle's documented PCI/virtio ancestry, outside /sys/devices/virtual."
  ["/sys/devices/pci0000:00/0000:00:04.0/virtio1/host2/target2:0:0/2:0:0:1/block/sda"
   {"/sys/devices/pci0000:00/0000:00:04.0/driver" "/sys/bus/pci/drivers/virtio-pci"
    "/sys/devices/pci0000:00/0000:00:04.0/virtio1/driver" "/sys/bus/virtio/drivers/virtio_scsi"} {} "8:0" 2048 false])

(defn- ^{:stratum 0} symbolic-link? [fixture path & _] (contains? (:fixture/links fixture) (str path)))

(defn- ^{:stratum 0} regular-file? [fixture path & _] (contains? (:fixture/files fixture) (str path)))

(defn- ^{:stratum 0} exists? [fixture path & _]
  (or (contains? (:fixture/links fixture) (str path)) (contains? (:fixture/files fixture) (str path))))

(defn- ^{:stratum 0} read-file [fixture path & _] (get-in fixture [:fixture/files (str path)]))

(defn- ^{:stratum 0} resolve-link [fixture path & _]
  (if-let [target (get-in fixture [:fixture/links (str path)])]
    (fs/path target)
    (throw (AssertionError.))))

(defn- ^{:stratum 0} attributes [fixture path & _]
  (let [same? (:fixture/same-device? fixture)
        backing (:fixture/device-number fixture)
        device (if (or same? (= (str path) "/workspace")) backing (inc backing))]
    {:dev device
     :ino 2}))

(defn- ^{:stratum 0} native-host [_ _ accepted-exits]
  (is (= #{1} accepted-exits))
  "none\n")

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} nvme
  "NVMe is local only when the controller reports PCIe transport."
  ["/sys/devices/pci0000:00/0000:01:00.0/nvme/nvme0/nvme0n1"
   {"/sys/devices/pci0000:00/0000:01:00.0/driver" "/sys/bus/pci/drivers/nvme"}
   {transport-path "pcie\n"} "259:0" 66304 true])

(defn ^{:stratum 1} kernel [[target links files device number _expected] same-device?]
  (let [link (str "/sys/dev/block/" device)
        links (assoc links link target)
        files (assoc files mounts (mount-row device))]
    {:fixture/links links
     :fixture/files files
     :fixture/device-number number
     :fixture/same-device? same-device?}))

(defn ^{:stratum 1} with-kernel [fixture action]
  (with-redefs [clojure.core/slurp (partial read-file fixture)
                fs/sym-link? (partial symbolic-link? fixture)
                fs/real-path (partial resolve-link fixture)
                fs/exists? (partial exists? fixture)
                fs/regular-file? (partial regular-file? fixture)
                fs/read-attributes (partial attributes fixture)
                probe/read-output native-host]
    (action)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} nvme-transport [value]
  (if (nil? value)
    (update nvme 2 dissoc transport-path)
    (assoc-in nvme [2 transport-path] value)))

(comment (kernel ahci true))
