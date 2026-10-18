;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.terminal-evidence-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.coerce.interface :as coerce]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as stream]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.pr-audit :as audit]
            [ai.miniforge.phase-opsv.terminal-test-support :as terminal :refer [with-ready]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} terminal-output [ctx]
  (get-in ctx [:phase :result :output :anomaly/data :opsv/phase-output]))

(defn- ^{:stratum 0} refuse-successful-audit [record refuse? ctx transaction]
  (if (and (= :succeeded (:effect/state transaction)) (refuse?))
    (anomaly/anomaly :unavailable "audit failed" {:effect/transaction transaction})
    (record ctx transaction)))

(defn- ^{:stratum 0} actuate-with-audit [ready record]
  (with-redefs [audit/record! record]
    (f/run-last-phase ready)))

(defn- ^{:stratum 0} refuse-disposition [publish event-stream event]
  (if (and (= :opsv.actuation/disposition (:event/type event))
           (= :succeeded (:opsv/effect-state event)))
    (anomaly/anomaly :unavailable "disposition unavailable" {})
    (publish event-stream event)))

(defn- ^{:stratum 0} checkpoint-round-trip [directory completed]
  (let [file (io/file directory "terminal-checkpoint.edn")
        checkpoint (coerce/stringify-instants
                    (select-keys completed [:execution/id :execution/input :execution/status]))]
    (spit file (pr-str checkpoint))
    (assoc (edn/read-string (slurp file)) :execution/opts
           (assoc (:execution/opts completed) :event-stream
                  (or (:event-stream completed) (get-in completed [:execution/opts :event-stream]))))))

(deftest ^{:stratum 0} recovery-rejects-nonterminal-contexts-test
  (doseq [ctx [nil {} {:execution/input 42} {:execution/opts 42}
               {:execution/opts :invalid} {:phase {:name :opsv/execute}}]]
    (is (= :invalid-input (:anomaly/type (opsv/recover-actuation-evidence! ctx))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-late-publication-recovery [kind ready directory calls]
  (let [completed (terminal/run-with-publication-failure ready kind)
        retained (terminal-output completed)
        recovered (opsv/recover-actuation-evidence! (checkpoint-round-trip directory completed))
        published (stream/get-events (:event-stream ready))
        again (opsv/recover-actuation-evidence! (checkpoint-round-trip directory completed))
        output (terminal-output recovered)]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (map? (:opsv/phase-failure retained)))
    (is (= :error (get-in recovered [:phase :result :status])))
    (is (map? (:opsv/evidence-bundle output)))
    (is (= (:opsv/effect-transactions retained) (:opsv/effect-transactions output)))
    (is (= published (stream/get-events (:event-stream ready))))
    (is (= (:opsv/evidence-bundle output) (:opsv/evidence-bundle (terminal-output again))))
    (is (= ["https://github.com/example/opsv/pull/17"]
           (get-in output [:opsv/actuation-record :pr-refs])))
    (is (= 2 (count @calls)))))

(defn- ^{:stratum 1} assert-persistent-audit-failure [ready _ calls]
  (let [record (partial refuse-successful-audit audit/record! (constantly true))
        completed (actuate-with-audit ready record)
        output (terminal-output completed)
        recovered (opsv/recover-actuation-evidence! (dissoc completed :opsv/evidence-assembly-store))
        again (opsv/recover-actuation-evidence! (dissoc recovered :opsv/evidence-assembly-store))]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (= :succeeded (get-in output [:opsv/effect-transactions 0 :effect/state])))
    (is (nil? (:opsv/evidence-bundle output)))
    (f/assert-blocked-transform completed)
    (f/assert-blocked-transform (update completed :execution/opts dissoc :opsv/evidence-base))
    (is (anomaly/anomaly? (get-in completed [:phase :result :output :anomaly/data :opsv/evidence-failure])))
    (is (= :assembling
           (:opsv.assembly/status
             (evidence/get-opsv-assembly (:opsv/evidence-assembly-store completed)
               (get-in completed [:execution/input :opsv/evidence-bundle-id])))))
    (is (= :error (get-in recovered [:phase :result :status])))
    (is (false? (get-in (terminal-output recovered) [:opsv/evidence-bundle :evidence/outcome :outcome/success])))
    (is (= :finalized (get-in recovered [:execution/input :opsv/evidence-assembly :opsv.assembly/status])))
    (is (nil? (get-in recovered [:phase :result :output :anomaly/data :opsv/evidence-failure])))
    (is (= (terminal-output recovered) (terminal-output again)))
    (is (= 2 (count @calls)))))

(defn- ^{:stratum 1} assert-checkpoint-recovery [ready directory calls]
  (let [record (partial refuse-successful-audit audit/record! (constantly true))
        completed (actuate-with-audit (assoc-in ready [:execution/workflow :workflow/tier] :critical) record)
        saved (checkpoint-round-trip directory completed)
        recovered (opsv/recover-actuation-evidence! saved)
        again (opsv/recover-actuation-evidence! (checkpoint-round-trip directory recovered))
        tampered (assoc-in saved [:execution/input :opsv/terminal-snapshot] "corrupt")]
    (is (nil? (:phase saved)))
    (is (nil? (:execution/workflow saved)))
    (is (map? (opsv/recover-actuation-evidence! (assoc saved :execution/opts nil))))
    (doseq [opts [42 :invalid [] "invalid"]]
      (is (= :invalid-input (:anomaly/type (opsv/recover-actuation-evidence!
                                           (assoc saved :execution/opts opts))))))
    (is (= :error (get-in recovered [:phase :result :status])))
    (is (= :failed (get-in recovered [:phase :status])))
    (doseq [key [:status :ended-at :duration-ms :metrics]]
      (is (= (get-in completed [:phase key]) (get-in recovered [:phase key]))))
    (is (= (get-in completed [:phase :result :metrics :duration-ms])
           (get-in recovered [:phase :result :metrics :duration-ms])))
    (is (= (:opsv/effect-transactions (terminal-output completed))
           (:opsv/effect-transactions (terminal-output recovered))))
    (is (false? (get-in (terminal-output recovered) [:opsv/evidence-bundle :evidence/outcome :outcome/success])))
    (is (= :critical (get-in (terminal-output recovered) [:opsv/evidence-bundle :evidence/outcome :outcome/tier])))
    (is (= (terminal-output recovered) (terminal-output again)))
    (is (anomaly/anomaly? (opsv/recover-actuation-evidence! tampered)))
    (is (= 2 (count @calls)))))

(defn- ^{:stratum 1} assert-pending-disposition [ready directory calls]
  (let [publish (partial refuse-disposition stream/publish!)
        completed (with-redefs [stream/publish! publish] (f/run-last-phase ready))
        event-stream (:event-stream ready)
        before (stream/get-events event-stream)
        _ (stream/publish! event-stream (stream/phase-started event-stream (:execution/id ready) :opsv/actuate))
        recovered (opsv/recover-actuation-evidence! (checkpoint-round-trip directory completed))
        output (terminal-output recovered)
        records (stream/get-events event-stream)
        bundle (:opsv/evidence-bundle output)]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (= 1 (count (:opsv/disposition-events (terminal-output completed)))))
    (is (= before (take (count before) records)))
    (is (= (range (count records)) (sort (map :event/sequence-number records))))
    (is (= (count records) (get-in @event-stream [:sequence-numbers (:execution/id ready)])))
    (is (= (count records) (get-in bundle [:evidence/event-links 0 :event-links/event-count])))
    (is (false? (get-in bundle [:evidence/outcome :outcome/success])))
    (is (= bundle (:opsv/evidence-bundle (terminal/recovery-output recovered))))
    (is (= records (stream/get-events event-stream)))
    (is (= 2 (count @calls)))))

(defn- ^{:stratum 1} assert-opt-in-required [ready _directory calls]
  (let [legacy (update ready :execution/opts dissoc :opsv/evidence-base)
        invalid (assoc-in legacy [:execution/opts :opsv/pr-execution :clock] nil)
        completed (f/run-last-phase invalid)
        corrected (assoc completed :execution/opts (:execution/opts ready))]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (map? (terminal-output completed)))
    (is (not (contains? (:execution/input completed) :opsv/terminal-snapshot)))
    (is (empty? @calls))
    (is (= :success (get-in (f/run-last-phase corrected) [:phase :result :status])))
    (is (= 2 (count @calls)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} post-actuation-publication-failures-recover-without-provider-replay-test
  (doseq [kind [:actuation :effect-transactions :evidence-bundle :event]]
    (with-ready (partial assert-late-publication-recovery kind))))

(deftest ^{:stratum 2} persistent-audit-failure-retains-outcome-without-finalizing-test
  (with-ready assert-persistent-audit-failure))

(deftest ^{:stratum 2} terminal-checkpoint-restores-exact-outcome-without-provider-replay-test
  (with-ready assert-checkpoint-recovery))

(deftest ^{:stratum 2} pending-disposition-retains-its-original-sequence-reservation
  (with-ready assert-pending-disposition))

(deftest ^{:stratum 2} legacy-caller-without-evidence-opt-in-can-correct-runtime-options
  (with-ready assert-opt-in-required))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.terminal-evidence-integration-test))
