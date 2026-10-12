;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-retention-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.workflow.chain :as chain]
            [ai.miniforge.workflow.chain-composition :as composition]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} definition [version workflow-id]
  {:chain/id :disk-retention :chain/version version
   :chain/steps [{:step/id :only :step/workflow-id workflow-id}]})

(defn- ^{:stratum 0} recover-at-dispatch [directory executed admitted _input _opts]
  (let [id (get-in admitted [:chain/run-record :chain/run-id])
        recovered (workflow/load-chain-run directory id)]
    (swap! executed conj recovered)
    recovered))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-record [version workflow-id]
  (composition/run-record (definition version workflow-id)))

(deftest ^{:stratum 1} runtime-admission-is-durable-before-dispatch-and-conflicts-stop-dispatch
  (fs/with-temp-dir [root {:prefix "chain-retention-gate-"}]
    (let [directory (str (fs/canonicalize root)) executed (atom [])
          opts {:chain/retention-directory directory}
          dispatch (partial recover-at-dispatch directory executed)]
      (with-redefs [chain/run-chain dispatch]
        (let [result (workflow/run-chain (definition "1.0.0" :first) {} opts)]
          (is (uuid? (:chain/run-id result)))
          (is (= (:chain/steps (definition "1.0.0" :first))
                 (get-in result [:chain/definition-snapshot :chain/steps])))
          (is (= :conflict (:anomaly/type (workflow/run-chain (definition "1.0.0" :changed) {} opts))))
          (is (= [result] @executed))
          (is (= 2 (count (artifact/list-published directory)))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} durable-retention-retries-and-enforces-immutable-identities
  (fs/with-temp-dir [root {:prefix "chain-retention-"}]
    (let [directory (str (fs/canonicalize root))
          original (run-record "1.0.0" :first)
          id (:chain/run-id original)
          conflicting (run-record "1.0.0" :changed)
          next-version (run-record "2.0.0" :changed)]
      (is (= :not-found (:anomaly/type (workflow/load-chain-run directory id))))
      (is (= original (workflow/persist-chain-run! directory original)))
      (is (= original (workflow/persist-chain-run! directory original)))
      (is (= original (workflow/load-chain-run directory id)))
      (is (= :conflict (:anomaly/type (workflow/persist-chain-run! directory conflicting))))
      (is (= :not-found (:anomaly/type (workflow/load-chain-run directory (:chain/run-id conflicting)))))
      (is (= :conflict (:anomaly/type (workflow/persist-chain-run! directory (assoc next-version :chain/run-id id)))))
      (is (= next-version (workflow/persist-chain-run! directory next-version)))
      (is (= next-version (workflow/load-chain-run directory (:chain/run-id next-version))))
      (is (= original (workflow/load-chain-run directory id))))))

(deftest ^{:stratum 2} corruption-is-not-masked-by-a-cached-admission
  (fs/with-temp-dir [root {:prefix "chain-retention-"}]
    (let [directory (str (fs/canonicalize root)) value (run-record "1.0.0" :first)]
      (is (= value (workflow/persist-chain-run! directory value)))
      (doseq [file (fs/list-dir root)] (spit (str file) "corrupt"))
      (is (anomaly/anomaly? (workflow/load-chain-run directory (:chain/run-id value)))))))

(comment
  (run-record "1.0.0" :example))
