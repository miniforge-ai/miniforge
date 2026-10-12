;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-retention-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.chain-composition :as composition]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-record [version workflow-id]
  (composition/run-record {:chain/id :disk-retention :chain/version version
                           :chain/steps [{:step/id :only :step/workflow-id workflow-id}]}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} durable-retention-retries-and-enforces-immutable-identities
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

(deftest ^{:stratum 1} corruption-is-not-masked-by-a-cached-admission
  (fs/with-temp-dir [root {:prefix "chain-retention-"}]
    (let [directory (str (fs/canonicalize root)) value (run-record "1.0.0" :first)]
      (is (= value (workflow/persist-chain-run! directory value)))
      (doseq [file (fs/list-dir root)] (spit (str file) "corrupt"))
      (is (anomaly/anomaly? (workflow/load-chain-run directory (:chain/run-id value)))))))

(comment
  (run-record "1.0.0" :example))
