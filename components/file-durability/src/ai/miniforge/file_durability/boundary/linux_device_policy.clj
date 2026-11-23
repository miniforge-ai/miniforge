;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-device-policy
  "Accept only recognized local-controller stacks, not an arbitrary sysfs location.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} device-tree
  "All observed ancestry must remain inside the kernel's device topology."
  "/sys/devices")

(def ^{:stratum 0} ^:private nvme-driver
  "PCI NVMe requires additional positive local transport evidence."
  "/sys/bus/pci/drivers/nvme")

(def ^{:stratum 0} ^:private ahci-driver
  "Local ATA controllers are the other initially supported transport."
  "/sys/bus/pci/drivers/ahci")

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private allowed-drivers
  "Unknown, virtual, USB and network-storage driver bindings cannot qualify."
  #{nvme-driver ahci-driver "/sys/bus/pci/drivers/pcieport" "/sys/bus/scsi/drivers/sd"})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} eligible? [ancestry]
  (let [drivers (remove nil? (map :device/driver ancestry))
        transports (set (remove nil? (map :device/transport ancestry)))
        nvme? (some #{nvme-driver} drivers)]
    (and (some #{nvme-driver ahci-driver} drivers)
         (every? allowed-drivers drivers)
         (if nvme? (= #{"pcie"} transports) (empty? transports)))))

(comment (eligible? []))
