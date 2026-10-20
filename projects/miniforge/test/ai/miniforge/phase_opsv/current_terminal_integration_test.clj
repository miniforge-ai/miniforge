;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.current-terminal-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.actuation :as actuation]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.terminal-test-support :as terminal]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} confirm-reopened [completed options records calls unknown?]
  (let [stream (events/create-current-event-stream options)
        replay-count (atom 0)
        detached (-> completed
                     (dissoc :opsv/evidence-assembly-store)
                     (assoc :event-stream stream)
                     (update :execution/opts dissoc :opsv/pr-execution :opsv/adapter))]
    (try+
      (with-redefs [actuation/actuate (partial terminal/reject-replay replay-count)]
        (let [recovered (opsv/recover-actuation-evidence! detached)
              output (get-in recovered [:phase :result :output :anomaly/data :opsv/phase-output])
              bundle (:opsv/evidence-bundle output)
              recovered-records (events/get-events stream)
              again (terminal/recovery-output detached)]
          (is (not (anomaly/any-anomaly? recovered)) (pr-str recovered))
          (is (= :error (get-in recovered [:phase :result :status])))
          (is (false? (get-in bundle [:evidence/outcome :outcome/success])))
          (is (= (if unknown? :unknown-outcome :succeeded)
                 (get-in output [:opsv/effect-transactions 0 :effect/state])))
          (is (= (if unknown? [] ["https://github.com/example/opsv/pull/17"])
                 (get-in output [:opsv/actuation-record :pr-refs])))
          (is (= bundle (:opsv/evidence-bundle again)))
          (is (= records (take (count records) recovered-records)))
          (is (= recovered-records (events/get-events stream)))
          (is (= (range (count recovered-records)) (map :event/sequence-number recovered-records)))
          (is (= 1 (count (filter #(= :opsv.actuation/emitted (:event/type %)) recovered-records))))
          (is (= (count recovered-records) (get-in bundle [:evidence/event-links 0 :event-links/event-count])))))
      (is (zero? @replay-count))
      (is (= ["GET" "POST"] (mapv #(get-in % [:arguments 6]) @calls)))
      (finally (events/close-current-event-stream! stream)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} interrupt-publication [options unknown? ready _directory calls]
  (let [ctx (cond-> ready
              unknown? (assoc-in [:execution/opts :opsv/pr-execution :provider :run-command]
                                (partial terminal/unsuccessful-command calls)))
        stream (:event-stream ctx)
        before (events/get-events stream)
        publish (partial f/reject-event-type :opsv.actuation/emitted events/publish!)
        completed (with-redefs [events/publish! publish] (f/run-last-phase ctx))
        records (events/get-events stream)]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (string? (get-in completed [:execution/input :opsv/terminal-snapshot])))
    (is (some #(= :gate/decision (:event/type %)) (drop (count before) records)))
    (f/assert-blocked-transform completed)
    (f/assert-blocked-transform (update completed :execution/opts dissoc :opsv/evidence-base))
    (events/close-current-event-stream! stream)
    (confirm-reopened completed options records calls unknown?)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} partial-terminal-publication-survives-journal-reopen-without-replay
  (doseq [unknown? [false true]]
    (fs/with-temp-dir [directory {:prefix "opsv-terminal-journal-"}]
      (let [options {:journal-directory (str (fs/real-path directory))}
            stream (events/create-current-event-stream options)]
        (try+
          (terminal/with-ready #(assoc % :event-stream stream)
                               (partial interrupt-publication options unknown?))
          (finally (events/close-current-event-stream! stream)))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.current-terminal-integration-test))
