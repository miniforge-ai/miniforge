;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-snapshot-schema-test
  (:require [ai.miniforge.phase-opsv.kubernetes-snapshot-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as fixture]
   [ai.miniforge.phase-opsv.interface :as phase]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-captured-read-resources-retain-native-json-shapes
  (is (m/validate schema/Snapshot (fixture/snapshot))))

(deftest ^{:stratum 0} test-native-lists-normalize-without-changing-the-fingerprint
  (let [captured (fixture/snapshot)
        without-types (partial mapv #(dissoc % "apiVersion" "kind"))
        native (-> captured
                   (update-in [:kubernetes/pods "items"] without-types)
                   (update-in [:kubernetes/nodes "items"] without-types))]
    (is (m/validate schema/Snapshot native))
    (is (= (phase/decode-kubernetes-fingerprint captured)
           (phase/decode-kubernetes-fingerprint native)))))

(deftest ^{:stratum 0} test-native-list-items-retain-type-identity-and-status-checks
  (let [without-types (partial mapv #(dissoc % "apiVersion" "kind"))
        native (-> (fixture/snapshot)
                   (update-in [:kubernetes/pods "items"] without-types)
                   (update-in [:kubernetes/nodes "items"] without-types))]
    (doseq [resource [:kubernetes/pods :kubernetes/nodes]
            [path value] [[["apiVersion"] "v2"]
                          [["kind"] "Other"]
                          [["kind"] nil]
                          [["metadata" "uid"] ""]
                          [["status"] {}]]]
      (let [malformed (assoc-in native (into [resource "items" 0] path) value)]
        (is (not (m/validate schema/Snapshot malformed)))))
    (doseq [resource [:kubernetes/pods :kubernetes/nodes]]
      (is (not (m/validate schema/Snapshot (update native resource dissoc "kind")))))
    (is (not (m/validate schema/Snapshot
                        (assoc-in native [:kubernetes/pods "items" 0 "spec"] {}))))))

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
