;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-snapshot-schema-test
  (:require [ai.miniforge.phase-opsv.kubernetes-snapshot-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as fixture]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-captured-read-resources-retain-native-json-shapes
  (is (m/validate schema/Snapshot (fixture/snapshot))))

(deftest ^{:stratum 0} test-incomplete-or-mutable-image-observations-are-refused
  (let [snapshot (fixture/snapshot)]
    (doseq [invalid [nil
                     (dissoc snapshot :kubernetes/node-pool-label)
                     (assoc snapshot :unknown true)
                     (assoc-in snapshot [:kubernetes/pods "items"] [])
                     (assoc-in snapshot [:kubernetes/nodes "items" 0 "metadata" "uid"] "")
                     (assoc-in snapshot [:kubernetes/pods "items" 0 "status" "containerStatuses" 0 "imageID"]
                               "registry.invalid/service:release")]]
      (is (false? (m/validate schema/Snapshot invalid))))))

(comment ::kubernetes-captured-snapshot-schema)
