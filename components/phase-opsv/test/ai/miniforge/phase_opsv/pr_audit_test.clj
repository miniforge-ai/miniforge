;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-audit-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.lifecycle :as lifecycle]
            [ai.miniforge.phase-opsv.pr-fixtures :as fixture]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} dispositions [ctx]
  (filterv #(= :opsv.actuation/disposition (:event/type %)) (events/get-events (:event-stream ctx))))

(defn- ^{:stratum 0} assembly [ctx]
  (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                             (get-in ctx [:execution/input :opsv/evidence-bundle-id])))

(deftest ^{:stratum 0} unavailable-audit-prevents-provider-io-test
  (let [{:keys [ctx runtime calls]} (fixture/setup)
        result (phase/actuate (dissoc ctx :event-stream))
        issued (grant/current (:authority-directory runtime) (get-in result [:anomaly/data :grant/id]))]
    (is (anomaly/anomaly? result))
    (is (= :proposed (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (= :revocation/superseded (:grant/revocation-reason issued)))
    (is (empty? @calls))))

(deftest ^{:stratum 0} post-mutation-publication-failure-preserves-confirmed-result-test
  (let [{:keys [ctx calls]} (fixture/setup)
        command (fn [options args]
                  (let [response (fixture/command calls options args)]
                    (when (= "POST" (nth args 6))
                      (events/quiesce! (:event-stream ctx) {:workflow-id (:execution/id ctx)}))
                    response))
        result (phase/actuate (assoc-in ctx [:execution/opts :opsv/pr-execution :provider :run-command] command))]
    (is (anomaly/anomaly? result))
    (is (= :succeeded (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (= 2 (count @calls)))))

(deftest ^{:stratum 0} failed-phase-publication-keeps-successful-effect-evidence-test
  (let [{:keys [ctx]} (fixture/setup)
        interceptor (lifecycle/interceptor {} :opsv/actuate phase/actuate)
        entered ((:enter interceptor) ctx)
        _ (events/quiesce! (:event-stream ctx) {:workflow-id (:execution/id ctx)})
        completed ((:leave interceptor) entered)
        output (get-in completed [:phase :result :output])]
    (is (= :failed (get-in completed [:phase :status])))
    (is (= :succeeded (get-in output [:anomaly/data :opsv/phase-output :opsv/effect-transactions 0 :effect/state])))
    (is (seq (get-in completed [:execution/input :opsv/evidence-assembly :opsv/grant-refs])))))

(deftest ^{:stratum 0} unconfirmed-evidence-accumulation-prevents-mutation-test
  (doseq [accumulate [(constantly nil) (fn [& _] (throw (ex-info "test evidence failure" {})))
                     (fn [& _] (throw (AssertionError. "test evidence failure")))]]
    (let [{:keys [ctx runtime calls]} (fixture/setup)
          result (with-redefs [evidence/accumulate-opsv-evidence! accumulate] (phase/actuate ctx))
          issued (grant/current (:authority-directory runtime) (get-in result [:anomaly/data :grant/id]))]
      (is (anomaly/anomaly? result))
      (is (= :proposed (get-in result [:anomaly/data :effect/transaction :effect/state])))
      (is (= :revocation/superseded (:grant/revocation-reason issued)))
      (is (empty? @calls)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} proposal-audit-precedes-provider-and-outcome-retains-authority-test
  (let [{:keys [ctx calls]} (fixture/setup)
        at-io (atom nil)
        command (fn [options args]
                  (when (nil? @at-io) (reset! at-io (dispositions ctx)))
                  (fixture/command calls options args))
        output (phase/actuate (assoc-in ctx [:execution/opts :opsv/pr-execution :provider :run-command] command))
        audit (dispositions ctx)
        effect-ref (first (get-in output [:opsv/actuation-record :governed-effects]))
        retained (assembly ctx)]
    (is (= [:proposed] (mapv :opsv/effect-state @at-io)))
    (is (= [:proposed :succeeded] (mapv :opsv/effect-state audit)))
    (is (every? #(= effect-ref (:opsv/governed-effect %)) audit))
    (is (every? #(= (:evidence/envelope-id effect-ref)
                    (get-in % [:opsv/decision-envelope :envelope/id])) audit))
    (is (= #{effect-ref} (:opsv/governed-effects retained)))
    (is (= #{(:evidence/grant-id effect-ref)} (:opsv/grant-refs retained)))
    (is (every? (:opsv/event-refs retained) (map :event/id audit)))))

(deftest ^{:stratum 1} uncertain-outcome-is-published-without-success-refs-test
  (let [{:keys [ctx calls]} (fixture/setup)
        command (fn [options args]
                  (let [response (fixture/command calls options args)]
                    (if (= "POST" (nth args 6)) {:exit 1} response)))
        result (phase/actuate (assoc-in ctx [:execution/opts :opsv/pr-execution :provider :run-command] command))
        audit (dispositions ctx)]
    (is (anomaly/anomaly? result))
    (is (= [:proposed :unknown-outcome] (mapv :opsv/effect-state audit)))
    (is (= {} (:opsv/effect-observed (last audit))))
    (is (every? (:opsv/event-refs (assembly ctx)) (map :event/id audit)))))

(deftest ^{:stratum 1} unconfirmed-commit-response-reloads-without-repeating-effect-test
  (let [{:keys [ctx calls]} (fixture/setup)
        commit actuation/commit-pr!
        result (with-redefs [actuation/commit-pr!
                            (fn [& args] (apply commit args) (anomaly/anomaly :unavailable "lost local response" {}))]
                 (phase/actuate ctx))]
    (is (anomaly/anomaly? result))
    (is (= :succeeded (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (= [:proposed :succeeded] (mapv :opsv/effect-state (dispositions ctx))))
    (is (= 2 (count @calls)))))

(deftest ^{:stratum 1} incompatible-assembly-refuses-proposal-before-publication-test
  (doseq [alter [#(assoc % :opsv.assembly/status :finalized)
                 #(assoc % :evidence-bundle/workflow-id (random-uuid))
                 (constantly nil)]]
    (let [{:keys [ctx runtime calls]} (fixture/setup)
          read-assembly evidence/get-opsv-assembly
          result (with-redefs [evidence/get-opsv-assembly (fn [& args] (alter (apply read-assembly args)))]
                   (phase/actuate ctx))
          issued (grant/current (:authority-directory runtime) (get-in result [:anomaly/data :grant/id]))]
      (is (anomaly/anomaly? result))
      (is (= :revocation/superseded (:grant/revocation-reason issued)))
      (is (empty? (dispositions ctx)))
      (is (empty? @calls)))))

(comment
  (dispositions (:ctx (fixture/setup))))
