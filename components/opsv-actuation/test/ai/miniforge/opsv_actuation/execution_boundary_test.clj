;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.execution-boundary-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.opsv-actuation.execution-fixtures
             :refer [now clock setup propose! propose-altered! commit! effect-report]]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} lost-response [calls record payload]
  (swap! calls conj [record payload])
  (throw (ex-info "provider response lost" {})))

(deftest ^{:stratum 0} runtime-boundary-rejects-unfulfilled-or-forged-decisions-test
  (let [{:keys [dir candidate grant decision calls]} (setup)
        reason {:reason/code :reason/gate-check-failed :reason/detail "denied"}
        obligation {:obligation/type :obligation/approval-required}]
    (doseq [invalid [nil (assoc decision :envelope/decision :deny)
                     (assoc decision :envelope/reasons [reason])
                     (assoc decision :envelope/obligations [obligation])]]
      (is (anomaly/anomaly?
           (actuation/propose-pr! dir candidate (:grant/id grant) invalid now))))
    (is (nil? (effect/read-record dir (:effect/id candidate))))
    (is (empty? @calls))))

(deftest ^{:stratum 0} malformed-runtime-arguments-never-invoke-provider-test
  (let [{:keys [dir candidate calls]} (setup)
        provider (partial effect-report calls)]
    (testing "missing clock/provider and invalid identity fail at the API boundary"
      (doseq [args [[dir dir nil clock provider]
                    [dir dir (:effect/id candidate) nil provider]
                    [dir dir (:effect/id candidate) clock nil]]]
        (is (anomaly/anomaly? (apply actuation/commit-pr! args)))))
    (is (empty? @calls))))

(deftest ^{:stratum 0} malformed-durable-payload-never-reaches-provider-test
  (doseq [[field value] [[:pr/title "altered"] [:pr/body "no evidence"]
                         [:pr/draft? true] [:pr/head-sha (apply str (repeat 40 "f"))]
                         [:effect/id (random-uuid)] [:opsv/envelope nil]]]
    (let [{:keys [calls] :as context} (setup)
          proposed (propose-altered! context #(assoc % field value))]
      (is (= :proposed (:effect/state proposed)))
      (is (not= :succeeded (:effect/state (commit! context))) (str field))
      (is (empty? @calls) (str field)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} provider-exception-leaves-durable-uncertainty-test
  (let [{:keys [dir candidate calls] :as context} (setup)
        proposed (propose! context)
        result (actuation/commit-pr! dir dir (:effect/id candidate)
                                    clock (partial lost-response calls))]
    (is (= :proposed (:effect/state proposed)))
    (is (= :unknown-outcome (:effect/state result)))
    (is (= "provider response lost" (:effect/failure result)))
    (is (= result (effect/read-record dir (:effect/id candidate))))
    (is (anomaly/anomaly? (commit! context)))
    (is (= 1 (count @calls)))))

(comment
  (setup))
