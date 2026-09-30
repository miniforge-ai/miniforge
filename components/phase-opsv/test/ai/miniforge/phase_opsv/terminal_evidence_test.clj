;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.terminal-evidence-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.coerce.interface :as coerce]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.pr-audit :as audit]
            [ai.miniforge.phase-opsv.pr-fixtures :as pr]
            [ai.miniforge.phase-opsv.terminal-test-support :refer [with-ready]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} terminal-output [ctx]
  (get-in ctx [:phase :result :output :anomaly/data :opsv/phase-output]))

(defn- ^{:stratum 0} unsuccessful-command [calls options arguments]
  (let [response (pr/command calls options arguments)]
    (if (= "POST" (get arguments 6)) {:exit 1 :out ""} response)))

(defn- ^{:stratum 0} refuse-successful-audit [record refuse? ctx transaction]
  (if (and (= :succeeded (:effect/state transaction)) (refuse?))
    (anomaly/anomaly :unavailable "audit failed" {:effect/transaction transaction})
    (record ctx transaction)))

(defn- ^{:stratum 0} actuate-with-audit [ready record]
  (with-redefs [audit/record! record]
    (f/run-last-phase ready)))

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

(defn- ^{:stratum 0} assert-confirmed-outcome [ready directory calls]
  (let [completed (f/run-last-phase ready)
        output (get-in completed [:phase :result :output])
        id (get-in output [:opsv/phase-artifact-ids :effect-transactions])
        published (artifact/read-published directory id)]
    (is (= :success (get-in completed [:phase :result :status])))
    (is (true? (get-in output [:opsv/evidence-bundle :evidence/outcome :outcome/success])))
    (is (= (:opsv/effect-transactions output) (:artifact/content published)))
    (is (= 2 (count @calls)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-unknown-outcome [ready directory calls]
  (let [completed (f/run-last-phase
                    (assoc-in ready [:execution/opts :opsv/pr-execution :provider :run-command]
                               (partial unsuccessful-command calls)))
        output (terminal-output completed)
        bundle (:opsv/evidence-bundle output)
        records (mapv #(artifact/read-published directory %) (:opsv/artifact-refs output))]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (false? (get-in bundle [:evidence/outcome :outcome/success])))
    (is (m/validate evidence/OpsvEvidence (:evidence/opsv bundle)))
    (is (= :unknown-outcome (get-in output [:opsv/effect-transactions 0 :effect/state])))
    (is (= 1 (count (get-in bundle [:evidence/opsv :opsv/grant-refs]))))
    (is (empty? (get-in bundle [:evidence/opsv :opsv/actuation :pr-refs])))
    (is (some #(= :effect-transactions (get-in % [:artifact/metadata :opsv/material-kind])) records))
    (is (= ["GET" "POST"] (mapv #(get-in % [:arguments 6]) @calls)))
    (is (= bundle (:opsv/evidence-bundle (opsv/publish-finalized-evidence! completed))))))

(defn- ^{:stratum 1} assert-retryable-audit-failure [ready _ calls]
  (let [fail-once? (atom true)
        refuse? (partial compare-and-set! fail-once? true false)
        record (partial refuse-successful-audit audit/record! refuse?)
        completed (actuate-with-audit ready record)
        output (terminal-output completed)
        bundle (:opsv/evidence-bundle output)]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (= :succeeded (get-in output [:opsv/effect-transactions 0 :effect/state])))
    (is (= :pr-only (get-in bundle [:evidence/opsv :opsv/actuation :effective-actuation-mode])))
    (is (= ["https://github.com/example/opsv/pull/17"]
           (get-in bundle [:evidence/opsv :opsv/actuation :pr-refs])))
    (is (false? (get-in bundle [:evidence/outcome :outcome/success])))
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
        completed (actuate-with-audit ready record)
        saved (checkpoint-round-trip directory completed)
        recovered (opsv/recover-actuation-evidence! saved)
        again (opsv/recover-actuation-evidence! (checkpoint-round-trip directory recovered))
        tampered (assoc-in saved [:execution/input :opsv/terminal-snapshot] "corrupt")]
    (is (nil? (:phase saved)))
    (is (= :error (get-in recovered [:phase :result :status])))
    (is (= (:opsv/effect-transactions (terminal-output completed))
           (:opsv/effect-transactions (terminal-output recovered))))
    (is (false? (get-in (terminal-output recovered) [:opsv/evidence-bundle :evidence/outcome :outcome/success])))
    (is (= (terminal-output recovered) (terminal-output again)))
    (is (anomaly/anomaly? (opsv/recover-actuation-evidence! tampered)))
    (is (= 2 (count @calls)))))

(deftest ^{:stratum 1} confirmed-provider-success-publishes-real-transaction-evidence-test
  (with-ready assert-confirmed-outcome))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} unknown-provider-outcome-finalizes-failure-with-real-transaction-test
  (with-ready assert-unknown-outcome))

(deftest ^{:stratum 2} failed-post-mutation-audit-retains-confirmed-provider-success-test
  (with-ready assert-retryable-audit-failure))

(deftest ^{:stratum 2} persistent-audit-failure-retains-outcome-without-finalizing-test
  (with-ready assert-persistent-audit-failure))

(deftest ^{:stratum 2} terminal-checkpoint-restores-exact-outcome-without-provider-replay-test
  (with-ready assert-checkpoint-recovery))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.terminal-evidence-test))
