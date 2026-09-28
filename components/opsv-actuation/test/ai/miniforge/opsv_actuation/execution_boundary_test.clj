;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.execution-boundary-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.opsv-actuation.execution-fixtures
             :refer [now clock setup propose! propose-altered! commit! effect-report]]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util Date]))

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
  (let [{:keys [dir candidate grant calls]} (setup)
        provider (partial effect-report calls)]
    (testing "missing clock/provider and invalid identity fail at the API boundary"
      (doseq [args [[dir dir nil (:grant/id grant) clock provider]
                    [dir dir (:effect/id candidate) nil clock provider]
                    [dir dir (:effect/id candidate) (:grant/id grant) nil provider]
                    [dir dir (:effect/id candidate) (:grant/id grant) clock nil]]]
        (is (anomaly/anomaly? (apply actuation/commit-pr! args)))))
    (is (empty? @calls))))

(deftest ^{:stratum 0} substituted-valid-authority-is-refused-before-claim-test
  (let [{:keys [dir grant-dir candidate grant calls] :as context} (setup)
        duplicate (grant/register! grant-dir (assoc grant :grant/id (random-uuid)))
        proposed (propose! (assoc context :grant duplicate))
        result (commit! context)]
    (is (not (anomaly/anomaly? duplicate)))
    (is (not= (:grant/id grant) (:grant/id duplicate)))
    (is (= (:grant/scope grant) (:grant/scope duplicate)))
    (is (= :unauthorized (:anomaly/type result)))
    (is (= :proposed (:effect/state proposed)))
    (is (= proposed (effect/read-record dir (:effect/id candidate))))
    (is (empty? @calls))))

(deftest ^{:stratum 0} malformed-durable-payload-never-reaches-provider-test
  (doseq [[field value] [[:pr/title "altered"] [:pr/body "no evidence"]
                         [:pr/draft? true] [:pr/head-sha (apply str (repeat 40 "f"))]
                         [:effect/id (random-uuid)] [:opsv/envelope nil]]]
    (let [{:keys [calls] :as context} (setup)
          proposed (propose-altered! context #(assoc % field value))]
      (is (= :proposed (:effect/state proposed)))
      (is (= :failed (:effect/state (commit! context))) (str field))
      (is (empty? @calls) (str field)))))

(deftest ^{:stratum 0} governance-corruption-is-refused-test
  (doseq [[path value] [[[:opsv/evidence-bundle-id] (random-uuid)]
                        [[:opsv/envelope :envelope/pins :pins/pack-revision] "changed"]
                        [[:opsv/envelope :envelope/at] (Date. 0)]
                        [[:pr/governance-hash] (apply str (repeat 64 "b"))]]]
    (let [{:keys [calls] :as context} (setup)
          proposed (propose-altered! context #(assoc-in % path value))]
      (is (= :proposed (:effect/state proposed)))
      (is (= :failed (:effect/state (commit! context))) (str path))
      (is (empty? @calls)))))

(deftest ^{:stratum 0} broad-grant-without-governance-binding-is-refused-test
  (let [{:keys [grant-dir calls] :as context} (setup)
        broad (update (:grant context) :grant/scope dissoc :pr/governance-hash)
        distinct-grant (assoc broad :grant/id (random-uuid))
        registered (grant/register! grant-dir distinct-grant)
        proposed (propose! (assoc context :grant registered))]
    (is (not (anomaly/anomaly? registered)))
    (is (= :proposed (:effect/state proposed)))
    (is (= :unauthorized (:anomaly/type (commit! (assoc context :grant registered)))))
    (is (empty? @calls))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} provider-exception-leaves-durable-uncertainty-test
  (let [{:keys [dir grant-dir candidate grant calls] :as context} (setup)
        proposed (propose! context)
        result (actuation/commit-pr! dir grant-dir (:effect/id candidate)
                                    (:grant/id grant) clock (partial lost-response calls))]
    (is (= :proposed (:effect/state proposed)))
    (is (= :unknown-outcome (:effect/state result)))
    (is (= "provider response lost" (:effect/failure result)))
    (is (= result (effect/read-record dir (:effect/id candidate))))
    (is (anomaly/anomaly? (commit! context)))
    (is (= 1 (count @calls)))))

(comment
  (setup))
