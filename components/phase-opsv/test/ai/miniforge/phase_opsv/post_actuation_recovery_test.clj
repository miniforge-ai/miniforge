;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.post-actuation-recovery-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as stream]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.lifecycle :as lifecycle]
            [ai.miniforge.phase-opsv.post-actuation-checkpoint :as checkpoint]
            [ai.miniforge.phase-opsv.test-support :as support]
            [ai.miniforge.phase-opsv.terminal-test-support :as terminal]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} counted-actuation [calls ctx]
  (swap! calls inc)
  (opsv/actuate ctx))

(defn- ^{:stratum 0} reject-actuation-material [publish directory record]
  (if (= :actuation (get-in record [:artifact/metadata :opsv/material-kind]))
    (anomaly/anomaly :unavailable "injected actuation publication failure" {})
    (publish directory record)))

(defn- ^{:stratum 0} reject-actuation-event [publish event-stream event]
  (if (= :opsv.actuation/emitted (:event/type event))
    (anomaly/anomaly :unavailable "injected actuation event failure" {})
    (publish event-stream event)))

(defn- ^{:stratum 0} complete-phase [interceptor ctx]
  ((:leave interceptor) ((:enter interceptor) ctx)))

(defn- ^{:stratum 0} assert-recovered [completed calls directory]
  (let [detached (dissoc completed :opsv/evidence-assembly-store)
        recovered (terminal/recovery-output detached)
        published-events (stream/get-events (:event-stream detached))
        again (terminal/recovery-output detached)
        bundle (:opsv/evidence-bundle recovered)]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (string? (get-in completed [:execution/input checkpoint/snapshot-key])))
    (is (anomaly/anomaly? (opsv/publish-finalized-evidence! detached)))
    (f/assert-blocked-transform detached)
    (f/assert-blocked-transform (update detached :execution/opts dissoc :opsv/evidence-base))
    (is (not (anomaly/anomaly? recovered)))
    (is (<= 8 (count (get-in bundle [:evidence/opsv :opsv/artifact-refs]))))
    (is (false? (get-in bundle [:evidence/outcome :outcome/success])))
    (is (= bundle (:opsv/evidence-bundle again)))
    (is (= bundle (:artifact/content (artifact/read-published directory (:opsv/evidence-artifact-id recovered)))))
    (is (= published-events (stream/get-events (:event-stream detached))))
    (is (= 1 @calls))
    (is (anomaly/anomaly? (terminal/recovery-output
                          (assoc-in detached [:execution/input :opsv/terminal-snapshot] "corrupt")))))
  (is (anomaly/anomaly? (terminal/recovery-output
                        (dissoc completed :opsv/evidence-assembly-store :event-stream)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-publication-recovery [ctx directory]
  (doseq [failure-kind [:material :event]]
    (let [ready (reduce f/step (f/configured ctx) (butlast support/handlers))
          calls (atom 0)
          interceptor (lifecycle/interceptor {} :opsv/actuate (partial counted-actuation calls))
          publish-material (partial reject-actuation-material artifact/publish!)
          publish-event (partial reject-actuation-event stream/publish!)
          completed (case failure-kind
                      :material (with-redefs [artifact/publish! publish-material]
                                  (complete-phase interceptor ready))
                      :event (with-redefs [stream/publish! publish-event]
                               (complete-phase interceptor ready)))]
      (assert-recovered completed calls directory))))

(defn- ^{:stratum 1} assert-snapshot-failure [ctx _directory]
  (let [ready (reduce f/step (f/configured ctx) (butlast support/handlers))
        calls (atom 0)
        interceptor (lifecycle/interceptor {} :opsv/actuate (partial counted-actuation calls))
        failure (anomaly/anomaly :unavailable "injected snapshot failure" {})
        completed (with-redefs [artifact/encode-snapshot (constantly failure)]
                    (complete-phase interceptor ready))]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (= failure (get-in completed [:execution/input checkpoint/snapshot-key])))
    (is (map? (get-in completed [:phase :result :output :anomaly/data :opsv/phase-output
                                :opsv/actuation-record])))
    (f/assert-blocked-transform completed)
    (is (= 1 @calls))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} publication-recovery-never-repeats-actuation-test
  (f/with-context assert-publication-recovery))

(deftest ^{:stratum 2} unencodable-actuation-output-cannot-report-success-test
  (f/with-context assert-snapshot-failure))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.post-actuation-recovery-test))
