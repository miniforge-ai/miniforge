;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.execution-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.opsv-actuation.interface-test :as fixture]
            [ai.miniforge.opsv-actuation.execution-fixtures
             :refer [now clock tmp-dir setup propose! commit!
                     effect-report uncertain-report]]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} expired-and-missing-authority-prevent-provider-test
  (let [{:keys [dir grant-dir candidate grant calls] :as context} (setup)
        expired-clock (constantly (.plusSeconds now 901))
        provider (partial effect-report calls)]
    (propose! context)
    (is (anomaly/anomaly?
         (actuation/commit-pr! dir (tmp-dir) (:effect/id candidate) (:grant/id grant) clock provider)))
    (is (= :proposed (:effect/state (effect/read-record dir (:effect/id candidate)))))
    (is (= :failed (:effect/state
                    (actuation/commit-pr! dir grant-dir (:effect/id candidate) (:grant/id grant)
                                          expired-clock provider))))
    (is (empty? @calls))))

(deftest ^{:stratum 0} durable-pr-commit-correlates-exact-payload-test
  (let [{:keys [dir candidate prepared grant decision calls] :as context} (setup)
        proposed (propose! context)
        committed (commit! context)
        [claimed payload] (first @calls)]
    (is (= :proposed (:effect/state proposed)))
    (is (= :succeeded (:effect/state committed)))
    (is (= :granted (:effect/authority committed)))
    (is (= (:grant/id grant) (:effect/grant-id committed)))
    (is (= (:envelope/id decision) (:effect/envelope-id committed)))
    (is (= (:opsv/evidence-bundle-id candidate)
           (get-in committed [:effect/proposal :opsv/evidence-bundle-id])))
    (is (= (update decision :envelope/at inst-ms)
           (update (get-in committed [:effect/proposal :opsv/envelope]) :envelope/at inst-ms)))
    (is (= :committing (:effect/state claimed)))
    (is (= (dissoc prepared :effect/id :workflow-run/id :pr/payload-hash) payload))
    (is (= {:pr/number 17} (:effect/observed committed)))
    (is (= committed (effect/read-record dir (:effect/id candidate))))
    (is (anomaly/anomaly? (commit! context)))
    (is (anomaly/anomaly? (propose! context)))
    (is (= 1 (count @calls)))))

(deftest ^{:stratum 0} revocation-between-proposal-and-commit-prevents-effect-test
  (let [{:keys [grant-dir grant calls] :as context} (setup)]
    (propose! context)
    (is (not (anomaly/anomaly?
              (grant/revoke-stored! grant-dir (:grant/id grant) :revocation/operator now))))
    (is (= :failed (:effect/state (commit! context))))
    (is (empty? @calls))))

(deftest ^{:stratum 0} uncertain-provider-outcome-is-not-retried-test
  (let [{:keys [dir grant-dir candidate grant calls] :as context} (setup)]
    (propose! context)
    (is (= :unknown-outcome
           (:effect/state (actuation/commit-pr! dir grant-dir (:effect/id candidate)
                                                (:grant/id grant) clock (partial uncertain-report calls)))))
    (is (anomaly/anomaly? (commit! context)))
    (is (= 1 (count @calls)))))

(deftest ^{:stratum 0} verification-failure-remains-draft-through-execution-test
  (let [candidate (assoc-in fixture/candidate [:opsv/verification-result :passed?] false)
        {:keys [calls] :as context} (setup candidate)]
    (propose! context)
    (is (= :succeeded (:effect/state (commit! context))))
    (is (true? (:pr/draft? (second (first @calls)))))))

(deftest ^{:stratum 0} verified-policy-hash-is-bound-to-durable-governance-test
  (let [hash (apply str (repeat 64 "a"))
        candidate (assoc fixture/candidate :opsv/policy-hash hash)
        {:keys [decision] :as context} (setup candidate)
        prepared (actuation/prepare-governed-pr candidate decision)
        altered (actuation/prepare-governed-pr
                  (assoc candidate :opsv/policy-hash (apply str (repeat 64 "b"))) decision)
        proposed (propose! context)
        committed (commit! context)]
    (is (= hash (:opsv/policy-hash prepared)))
    (is (= (:pr/payload-hash prepared) (:pr/payload-hash altered)))
    (is (not= (:pr/governance-hash prepared) (:pr/governance-hash altered)))
    (is (= hash (get-in proposed [:effect/proposal :opsv/policy-hash])))
    (is (= :succeeded (:effect/state committed)))
    (is (= hash (get-in committed [:effect/proposal :opsv/policy-hash])))))

(comment
  (setup))
