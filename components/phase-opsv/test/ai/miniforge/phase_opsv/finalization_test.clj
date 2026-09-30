;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.finalization-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.finalization-boundary :as finalization]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.lifecycle :as lifecycle]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} actuate-with-publisher [ctx publish]
  (with-redefs [artifact/publish! publish]
    (f/run-last-phase ctx)))

(defn- ^{:stratum 0} interrupt-publication []
  (throw (InterruptedException. "publication interrupted")))

(defn- ^{:stratum 0} publish-unless-bundle [publish failure root record]
  (if (= :evidence-bundle (get-in record [:artifact/metadata :opsv/material-kind]))
    (failure)
    (publish root record)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-finalized-evidence [ctx directory]
  (let [completed (reduce f/step (f/configured ctx) support/handlers)
        output (support/phase-output completed :opsv/actuate)
        bundle (:opsv/evidence-bundle output)
        assembly (get-in completed [:execution/input :opsv/evidence-assembly])]
    (is (m/validate evidence/OpsvEvidence (:evidence/opsv bundle)))
    (is (= :finalized (:opsv.assembly/status assembly)))
    (is (= (:evidence-bundle/id assembly) (:opsv/evidence-artifact-id output)))
    (is (= bundle (:artifact/content (artifact/read-published directory (:evidence-bundle/id bundle)))))
    (is (= (hash/content-hash (dissoc bundle :evidence/content-hash)) (:evidence/content-hash bundle)))
    (is (= (:opsv/event-refs assembly) (set (get-in bundle [:evidence/opsv :opsv/event-refs]))))
    (is (= 8 (count (get-in bundle [:evidence/opsv :opsv/artifact-refs]))))))

(defn- ^{:stratum 1} assert-invalid-host-evidence [ctx _]
  (doseq [base [nil :invalid {} (assoc (get-in (f/configured ctx) [:execution/opts :opsv/evidence-base])
                                      :evidence-bundle/workflow-id (random-uuid))]]
    (let [calls (atom 0)
          interceptor (lifecycle/interceptor {} :opsv/execute (fn [_] (swap! calls inc)))
          result ((:enter interceptor) (assoc-in ctx [:execution/opts :opsv/evidence-base] base))]
      (is (= :error (get-in result [:phase :result :status])))
      (is (zero? @calls)))))

(defn- ^{:stratum 1} assert-publication-recovery [ctx directory]
  (let [ready (reduce f/step (f/configured ctx) (butlast support/handlers))
        refusal (constantly (anomaly/anomaly :unavailable "disk barrier failed" {}))
        publish (partial publish-unless-bundle artifact/publish! refusal)
        completed (actuate-with-publisher ready publish)
        failure (get-in completed [:phase :result :output])
        assembly (get-in completed [:execution/input :opsv/evidence-assembly])
        restored (runtime/ensure-assembly (dissoc completed :opsv/evidence-assembly-store))
        recovered (opsv/publish-finalized-evidence! restored)
        again (opsv/publish-finalized-evidence! restored)]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (map? (get-in failure [:anomaly/data :opsv/phase-output :opsv/actuation-record])))
    (is (= :finalized (:opsv.assembly/status assembly)))
    (is (= (:opsv.assembly/bundle assembly) (:opsv/evidence-bundle recovered)))
    (is (= recovered again))
    (is (= (:opsv/evidence-bundle recovered)
           (:artifact/content (artifact/read-published directory (:opsv/evidence-artifact-id recovered)))))))

(defn- ^{:stratum 1} assert-material-integrity [ctx _]
  (let [completed (reduce f/step ctx support/handlers)
        output (support/phase-output completed :opsv/actuate)
        configured-ctx (f/configured completed)
        missing (with-redefs [artifact/read-published (constantly nil)]
                  (finalization/finalize! configured-ctx output))
        changed (finalization/finalize! configured-ctx
                  (assoc-in output [:opsv/actuation-record :pr-refs] ["invented-pr"]))]
    (is (anomaly/anomaly? missing))
    (is (anomaly/anomaly? changed))
    (is (= output (get-in missing [:anomaly/data :opsv/phase-output])))
    (is (= :assembling (:opsv.assembly/status
                       (evidence/get-opsv-assembly (:opsv/evidence-assembly-store completed)
                         (get-in completed [:execution/input :opsv/evidence-bundle-id])))))))

(defn- ^{:stratum 1} assert-replay-refused [ctx _]
  (let [completed (reduce f/step (f/configured ctx) support/handlers)
        calls (atom 0)
        interceptor (lifecycle/interceptor {} :opsv/execute (fn [_] (swap! calls inc)))
        replay ((:enter interceptor) completed)
        altered (-> completed
                    (dissoc :opsv/evidence-assembly-store)
                    (assoc-in [:execution/input :opsv/evidence-snapshot] "corrupt"))
        recovered (opsv/publish-finalized-evidence! (runtime/ensure-assembly altered))]
    (is (= :error (get-in replay [:phase :result :status])))
    (is (zero? @calls))
    (is (anomaly/anomaly? recovered))))

(defn- ^{:stratum 1} assert-interrupted-publication [ctx _]
  (let [ready (reduce f/step (f/configured ctx) (butlast support/handlers))
        publish (partial publish-unless-bundle artifact/publish! interrupt-publication)
        completed (actuate-with-publisher ready publish)
        interrupted? (Thread/interrupted)]
    (is interrupted?)
    (is (= :error (get-in completed [:phase :result :status])))
    (is (= :finalized (get-in completed [:execution/input :opsv/evidence-assembly :opsv.assembly/status])))
    (is (map? (get-in completed [:phase :result :output :anomaly/data :opsv/phase-output
                                 :opsv/actuation-record])))
    (is (not (anomaly/anomaly? (opsv/publish-finalized-evidence! completed))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} lifecycle-finalizes-real-n6-and-publishes-preallocated-id-test
  (f/with-context assert-finalized-evidence))

(deftest ^{:stratum 2} invalid-host-evidence-refuses-before-transform-test
  (f/with-context assert-invalid-host-evidence))

(deftest ^{:stratum 2} publication-failure-retains-bundle-and-recovers-without-actuation-test
  (f/with-context assert-publication-recovery))

(deftest ^{:stratum 2} missing-or-mismatched-material-refuses-finalization-test
  (f/with-context assert-material-integrity))

(deftest ^{:stratum 2} finalized-run-refuses-phase-replay-and-tampered-recovery-test
  (f/with-context assert-replay-refused))

(deftest ^{:stratum 2} exception-after-finalization-checkpoints-recovery-state-test
  (f/with-context assert-interrupted-publication))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.finalization-test))
