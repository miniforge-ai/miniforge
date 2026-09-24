;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.actuation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.opsv.interface :as opsv]
   [ai.miniforge.phase-opsv.interface :as phase-opsv]
   [ai.miniforge.phase-opsv.test-support :as support]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} verified-output
  {:opsv/verification-result {:passed? true}
   :opsv/policy-hash "retained-policy-hash"})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} requested-context
  [mode safe-mode?]
  (-> (support/execution-context nil)
      (assoc-in [:execution/input :opsv/experiment-pack
                 :experiment-pack/actuation-intent] mode)
      (update :execution/input assoc
              :opsv/safe-mode? safe-mode?
              :opsv/pr-capability-valid? true
              :opsv/apply-capability-valid? true
              :opsv/rollback-verified? true
              :opsv/postconditions-configured? true)
      (assoc-in [:execution/phase-results :opsv/verify :result :output]
                verified-output)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} caller-flags-cannot-create-execution-authority-test
  (doseq [mode opsv/requested-actuation-modes
          safe-mode? [false true]]
    (let [ctx (requested-context mode safe-mode?)
          output (phase-opsv/actuate ctx)
          record (:opsv/actuation-record output)
          expected-mode (if safe-mode? :none :recommend-only)]
      (testing (str "requested " mode ", safe mode " safe-mode?)
        (is (= verified-output (dissoc output :opsv/actuation-record)))
        (is (= mode (:requested-actuation-mode record)))
        (is (= expected-mode (:effective-actuation-mode record)))
        (is (= record (opsv/validate-actuation record)))
        (is (= :not-required (get-in record [:rollback :status])))
        (doseq [field [:governed-effects :pr-refs :apply-refs
                      :postcondition-artifact-refs]]
          (is (= [] (get record field))))))))

(deftest ^{:stratum 2} invalid-decision-still-fails-closed-test
  (testing "unknown requested intent is not silently replaced"
    (is (anomaly/anomaly?
         (phase-opsv/actuate (requested-context :unknown false)))))
  (testing "missing verification cannot produce a successful record"
    (let [ctx (dissoc (requested-context :apply-allowed false)
                      :execution/phase-results)]
      (is (anomaly/anomaly? (phase-opsv/actuate ctx))))))

(comment
  (phase-opsv/actuate (requested-context :apply-allowed false)))
