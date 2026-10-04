;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.actuation-snapshot-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.actuation-snapshot :as snapshot]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.evidence-base :as base]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.evidence-snapshot :as envelope]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} assert-bound-base [ctx _directory]
  (let [ready (runtime/ensure-assembly (f/configured ctx))
        original (base/bundle ready)
        output {:opsv/actuation-record {:effect :completed}}
        encoded (snapshot/encode ready output)]
    (doseq [restarted [(update ready :execution/opts dissoc :opsv/evidence-base)
                       (assoc-in ready [:execution/opts :opsv/evidence-base :evidence-bundle/version] "changed")
                       (assoc-in ready [:execution/opts :opsv/evidence-base :evidence/intent :intent/description] "changed")]]
      (let [restored (snapshot/restore-context restarted encoded)]
        (is (= original (get-in restored [:execution/opts :opsv/evidence-base])))
        (is (= output (:opsv/recovered-actuation-output restored)))))
    (is (anomaly/anomaly? (snapshot/restore-context ready "corrupt")))
    (is (anomaly/anomaly? (snapshot/restore-context ready (envelope/encode ready :post-actuation output))))
    (is (anomaly/anomaly? (snapshot/restore-context (assoc ready :execution/id (random-uuid)) encoded)))
    (is (anomaly/anomaly? (snapshot/restore-context
                          (assoc-in ready [:execution/input :opsv/evidence-bundle-id] (random-uuid)) encoded)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} actuation-checkpoint-retains-the-preflight-base-test
  (f/with-context assert-bound-base))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.actuation-snapshot-test))
