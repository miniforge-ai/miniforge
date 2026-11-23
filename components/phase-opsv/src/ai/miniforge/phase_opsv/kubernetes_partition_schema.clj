;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-partition-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-membership-schema :as membership]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory-schema :as inventory]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Partition
  [:map {:closed true}
   [:kubernetes/inventory inventory/Observation]
   [:kubernetes/membership membership/Observation]])

(comment ::schema-alone-does-not-prove-the-partition-corresponds-to-the-source)
