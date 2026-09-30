;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-boundary-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.governance-fixtures :as governance]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.pr-fixtures :as fixture]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} replay-never-creates-a-second-effect-test
  (let [{:keys [ctx calls]} (fixture/setup)
        first-run (phase/actuate ctx)
        replay (phase/actuate ctx)]
    (is (= :pr-only (get-in first-run [:opsv/actuation-record :effective-actuation-mode])))
    (is (= :conflict (:anomaly/type replay)))
    (is (= :succeeded (get-in replay [:anomaly/data :effect/transaction :effect/state])))
    (is (= 2 (count @calls)))))

(deftest ^{:stratum 0} recommendation-and-input-safe-mode-never-create-effects-test
  (doseq [[path value expected]
          [[[:execution/input :opsv/experiment-pack :experiment-pack/actuation-intent]
            :recommend-only :recommend-only]
           [[:execution/input :opsv/safe-mode?] true :none]
           [[:execution/opts :opsv/governance :policy/context :opsv/allowed-environments]
            #{} :recommend-only]]]
    (let [{:keys [ctx calls]} (fixture/setup)
          output (phase/actuate (assoc-in ctx path value))]
      (is (= expected (get-in output [:opsv/actuation-record :effective-actuation-mode])))
      (is (empty? @calls)))))

(deftest ^{:stratum 0} stopped-runtime-fence-forces-none-test
  (let [{:keys [ctx runtime calls]} (fixture/setup)]
    (actuation/stop-mutations! (:fence runtime))
    (is (= :none (get-in (phase/actuate ctx) [:opsv/actuation-record :effective-actuation-mode])))
    (is (empty? @calls))))

(deftest ^{:stratum 0} failed-verification-can-only-produce-a-draft-pr-test
  (let [{:keys [ctx calls]} (fixture/setup)
        requested (-> ctx
                      (assoc-in [:execution/input :opsv/experiment-pack :experiment-pack/actuation-intent]
                                :apply-allowed)
                      (assoc-in (conj governance/output-path :opsv/verification-result :passed?) false))
        result (phase/actuate requested)
        body (json/parse-string-strict (get-in @calls [1 :options :in]) true)]
    (is (= :pr-only (get-in result [:opsv/actuation-record :effective-actuation-mode])))
    (is (true? (:draft body)))))

(deftest ^{:stratum 0} expired-authority-prevents-provider-io-test
  (let [{:keys [ctx runtime calls]} (fixture/setup)
        reads (atom 0)
        clock #(if (= 1 (swap! reads inc)) fixture/now (.plusSeconds fixture/now 901))
        result (phase/actuate (assoc-in ctx [:execution/opts :opsv/pr-execution :clock] clock))]
    (is (anomaly/anomaly? result))
    (is (= :failed (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (some? (:grant/revoked-at (grant/current (:authority-directory runtime)
                                                (get-in result [:anomaly/data :grant/id])))))
    (is (empty? @calls))))

(deftest ^{:stratum 0} stop-after-issuance-revokes-before-provider-io-test
  (let [{:keys [ctx runtime calls]} (fixture/setup)
        reads (atom 0)
        clock #(do (when (= 2 (swap! reads inc)) (actuation/stop-mutations! (:fence runtime))) fixture/now)
        result (phase/actuate (assoc-in ctx [:execution/opts :opsv/pr-execution :clock] clock))
        transaction (get-in result [:anomaly/data :effect/transaction])
        issued (grant/current (:authority-directory runtime) (:effect/grant-id transaction))]
    (is (= :failed (:effect/state transaction)))
    (is (some? (:grant/revoked-at issued)))
    (is (empty? @calls))))

(deftest ^{:stratum 0} lost-response-persists-uncertainty-and-replay-does-not-post-test
  (let [{:keys [ctx runtime calls]} (fixture/setup)
        command (fn [options args]
                  (let [response (fixture/command calls options args)]
                    (if (= "POST" (nth args 6)) {:exit 1} response)))
        configured (assoc-in ctx [:execution/opts :opsv/pr-execution :provider :run-command] command)
        output (phase/actuate configured)
        transaction (get-in output [:anomaly/data :effect/transaction])]
    (is (= :unavailable (:anomaly/type output)))
    (is (= :unknown-outcome (:effect/state transaction)))
    (is (= transaction (effect/read-record (:effects-directory runtime) (:effect/id transaction))))
    (is (= :conflict (:anomaly/type (phase/actuate configured))))
    (is (= 2 (count @calls)))))

(deftest ^{:stratum 0} stop-during-provider-preflight-prevents-post-test
  (doseq [[stop? preflight] [[true :valid] [true :unavailable] [false :unavailable] [false :mismatch]]]
  (let [{:keys [ctx runtime calls]} (fixture/setup)
        command (fn [options args]
                  (when stop? (actuation/stop-mutations! (:fence runtime)))
                  (let [response (fixture/command calls options args)]
                    (case preflight
                      :unavailable {:exit 1}
                      :mismatch (update response :out str/replace (:pr/head-sha fixture/target) (apply str (repeat 40 "c")))
                      response)))
        result (phase/actuate (assoc-in ctx [:execution/opts :opsv/pr-execution :provider :run-command]
                                       command))
        transaction (get-in result [:anomaly/data :effect/transaction])
        issued (grant/current (:authority-directory runtime) (:effect/grant-id transaction))]
    (is (= :failed (:effect/state transaction)))
    (is (= (if stop? :revocation/operator :revocation/superseded) (:grant/revocation-reason issued)))
    (is (= ["GET"] (mapv #(get-in % [:arguments 6]) @calls))))))

(deftest ^{:stratum 0} expiry-during-provider-preflight-prevents-post-test
  (doseq [clock-value [(.plusSeconds fixture/now 901) (ex-info "clock failed" {}) nil]]
  (let [{:keys [ctx runtime calls]} (fixture/setup)
        now (atom fixture/now)
        command (fn [options args]
                  (reset! now clock-value)
                  (fixture/command calls options args))
        configured (-> ctx
                       (assoc-in [:execution/opts :opsv/pr-execution :clock]
                                 #(if (instance? Throwable @now) (throw @now) @now))
                       (assoc-in [:execution/opts :opsv/pr-execution :provider :run-command] command))
        result (phase/actuate configured)
        transaction (get-in result [:anomaly/data :effect/transaction])
        issued (grant/current (:authority-directory runtime) (:effect/grant-id transaction))]
    (is (= :failed (get-in result [:anomaly/data :effect/transaction :effect/state])))
    (is (= :revocation/superseded (:grant/revocation-reason issued)))
    (is (= ["GET"] (mapv #(get-in % [:arguments 6]) @calls))))))

(deftest ^{:stratum 0} stop-during-post-revokes-without-rewriting-provider-outcome-test
  (doseq [uncertain? [false true] broken-clock? [false true]]
    (let [{:keys [ctx runtime calls]} (fixture/setup)
          after-post? (atom false)
          clock #(if (and broken-clock? @after-post?) (throw (ex-info "clock failed" {})) fixture/now)
          command (fn [options args]
                    (let [response (fixture/command calls options args)]
                      (if (= "POST" (nth args 6))
                        (do (actuation/stop-mutations! (:fence runtime))
                            (reset! after-post? true)
                            (if uncertain? {:exit 1} response))
                        response)))
          output (phase/actuate (-> ctx
                                   (assoc-in [:execution/opts :opsv/pr-execution :provider :run-command] command)
                                   (assoc-in [:execution/opts :opsv/pr-execution :clock] clock)))
          transaction (or (first (:opsv/effect-transactions output))
                          (get-in output [:anomaly/data :effect/transaction]))
          issued (grant/current (:authority-directory runtime) (:effect/grant-id transaction))]
      (is (= (if uncertain? :unknown-outcome :succeeded) (:effect/state transaction)))
      (is (some? (:grant/revoked-at issued)))
      (is (= 2 (count @calls))))))

(comment
  (phase/actuate (:ctx (fixture/setup))))
