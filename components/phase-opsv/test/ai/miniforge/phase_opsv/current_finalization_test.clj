;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.current-finalization-test
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
            directory (get-in ctx [:execution/opts :opsv/artifact-directory])
            stored (artifact/read-published directory (:opsv/evidence-artifact-id first-recovery))]
        (is (not (anomaly/any-anomaly? first-recovery)) (pr-str first-recovery))
        (is (:valid? (evidence/validate-published-bundle bundle)))
        (is (= bundle (:artifact/content stored)))
        (is (= :finalized (get-in restored [:execution/input :opsv/evidence-assembly :opsv.assembly/status])))
        (is (= first-recovery (opsv/publish-finalized-evidence! restored)))
        (is (= records (events/get-events stream)))
        (is (= (count records) (get-in bundle [:evidence/event-links 0 :event-links/event-count])))
        (fixture/assert-blocked-transform restored))
      (finally (events/close-current-event-stream! stream)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-current [fail-publication? ctx _directory]
  (fs/with-temp-dir [directory {:prefix "opsv-finalization-journal-"}]
    (let [path (str (fs/real-path directory))
          options {:journal-directory path}
          stream (events/create-current-event-stream options)
          configured (assoc (fixture/configured ctx) :event-stream stream)
          publisher (if fail-publication? (partial publish-unless-bundle artifact/publish!) artifact/publish!)]
      (try+
        (let [ready (reduce fixture/step configured (butlast support/handlers))
              receipt (events/publish! stream (phase-start ready))
              completed (with-redefs [artifact/publish! publisher] (fixture/run-last-phase ready))
              expected-status (if fail-publication? :error :success)
              records (events/get-events stream)]
          (is (= expected-status (get-in completed [:phase :result :status])))
          (is (nat-int? (:event/sequence-number receipt)))
          (is (= (range (count records)) (map :event/sequence-number records)))
          (events/close-current-event-stream! stream)
          (confirm-reopened completed options records))
        (finally (events/close-current-event-stream! stream))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} current-journal-finalization-and-publication-recovery
  (doseq [fail-publication? [false true]]
    (fixture/with-context (partial run-current fail-publication?))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.current-finalization-test))
