;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.current-finalization-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.artifact-test-support :as fixture]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.test-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} phase-start [ctx]
  {:event/id (random-uuid) :event/timestamp (java.util.Date.)
   :event/version "1.0.0" :event/type :workflow/phase-started :scope/type :workflow
   :message "fixture actuation phase started"
   :workflow/id (:execution/id ctx) :workflow/phase :opsv/actuate
   :phase/expected-agent :opsv :phase/context {}})

(defn- ^{:stratum 0} publish-unless-bundle [publish directory record]
  (if (= :evidence-bundle (get-in record [:artifact/metadata :opsv/material-kind]))
    (anomaly/anomaly :unavailable "fixture bundle publication unavailable" {})
    (publish directory record)))

(defn- ^{:stratum 0} confirm-reopened [ctx options records]
  (let [stream (events/create-current-event-stream options)
        restarted (-> ctx (dissoc :opsv/evidence-assembly-store)
                      (assoc :event-stream stream)
                      (update :execution/opts dissoc :opsv/adapter))]
    (try+
      (let [restored (runtime/ensure-assembly restarted)
            first-recovery (opsv/publish-finalized-evidence! restored)
            bundle (:opsv/evidence-bundle first-recovery)
            recovered-records (events/get-events stream)
            directory (get-in ctx [:execution/opts :opsv/artifact-directory])
            stored (artifact/read-published directory (:opsv/evidence-artifact-id first-recovery))]
        (is (not (anomaly/any-anomaly? first-recovery)) (pr-str first-recovery))
        (is (:valid? (evidence/validate-published-bundle bundle)))
        (is (= bundle (:artifact/content stored)))
        (is (= :finalized (:opsv.assembly/status
                           (evidence/get-opsv-assembly (:opsv/evidence-assembly-store restored)
                                                       (:evidence-bundle/id bundle)))))
        (is (= (select-keys first-recovery [:opsv/evidence-bundle :opsv/evidence-artifact-id])
               (opsv/publish-finalized-evidence! restored)))
        (is (= records (take (count records) recovered-records)))
        (is (= recovered-records (events/get-events stream)))
        (is (= (range (count recovered-records)) (map :event/sequence-number recovered-records)))
        (is (= (count recovered-records) (get-in bundle [:evidence/event-links 0 :event-links/event-count])))
        (fixture/assert-blocked-transform restored))
      (finally (events/close-current-event-stream! stream)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-current [failure-kind ctx _directory]
  (fs/with-temp-dir [directory {:prefix "opsv-finalization-journal-"}]
    (let [path (str (fs/real-path directory))
          options {:journal-directory path}
          stream (events/create-current-event-stream options)
          configured (assoc (fixture/configured ctx) :event-stream stream)
          publisher (if (= :bundle failure-kind) (partial publish-unless-bundle artifact/publish!) artifact/publish!)
          event-publisher (if (= :event failure-kind)
                            (partial fixture/reject-event-type :opsv.actuation/emitted events/publish!)
                            events/publish!)]
      (try+
        (let [ready (reduce fixture/step configured (butlast support/handlers))
              receipt (events/publish! stream (phase-start ready))
              completed (with-redefs [artifact/publish! publisher events/publish! event-publisher]
                          (fixture/run-last-phase ready))
              expected-status (if (= :none failure-kind) :success :error)
              records (events/get-events stream)]
          (is (= expected-status (get-in completed [:phase :result :status])))
          (is (nat-int? (:event/sequence-number receipt)))
          (is (= (range (count records)) (map :event/sequence-number records)))
          (events/close-current-event-stream! stream)
          (confirm-reopened completed options records))
        (finally (events/close-current-event-stream! stream))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} current-journal-finalization-and-publication-recovery
  (doseq [failure-kind [:none :bundle :event]]
    (fixture/with-context (partial run-current failure-kind))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.current-finalization-integration-test))
