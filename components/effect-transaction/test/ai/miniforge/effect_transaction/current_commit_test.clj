;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.current-commit-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.fixtures :as fixture]
            [ai.miniforge.effect-transaction.interface :as fx]
            [ai.miniforge.execution-grant.interface :as grant]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capture-success!
  [received t]
  (swap! received conj t)
  {:effect/outcome :succeeded :effect/observed (:effect/proposal t)})

(defn- ^{:stratum 0} current-grant
  [grants id]
  (get @grants id))

(defn- ^{:stratum 0} read-clock
  [order]
  (swap! order conj :clock)
  fixture/now)

(defn- ^{:stratum 0} read-grant
  [order g id]
  (swap! order conj [:grant id])
  g)

(defn- ^{:stratum 0} capture-durable!
  [dir received t]
  (swap! received conj [t (fx/read-record dir (:effect/id t))])
  {:effect/outcome :succeeded})

(defn- ^{:stratum 0} throw-effect!
  [_t]
  (fixture/throw-exception! "response lost"))

(defn- ^{:stratum 0} throw-port!
  [& _args]
  (fixture/throw-exception! "port unavailable"))

(defn- ^{:stratum 0} competing-commit!
  [dir t g _id]
  (fx/commit! dir t g {} fixture/now fixture/succeed!)
  g)

(deftest ^{:stratum 0} malformed-runtime-functions-are-input-anomalies-test
  (doseq [ports [[nil identity identity] [identity :clock identity]
                 [identity identity {}]]]
    (let [arguments (concat [(fixture/tmp-dir) (random-uuid)] ports)]
      (is (= :invalid-input (:anomaly/type (apply fx/commit-current! arguments)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} exact-claimed-record-reaches-executor-test
  (let [dir (fixture/tmp-dir)
        {g :grant t :transaction} (fixture/merge-case! dir)
        order (atom [])
        received (atom [])
        done (fx/commit-current! dir (:effect/id t)
                                 (partial read-grant order g)
                                 (partial read-clock order)
                                 (partial capture-durable! dir received))
        claimed (ffirst @received)]
    (is (= [[:grant (:grant/id g)] :clock] @order))
    (is (= :succeeded (:effect/state done)))
    (is (= 1 (count @received)))
    (is (apply = (first @received)))
    (is (= :committing (:effect/state claimed)))
    (is (= :granted (:effect/authority claimed)))
    (is (= (:effect/proposal t) (:effect/proposal claimed)))
    (is (= (:effect/envelope-id t) (:effect/envelope-id claimed)))))

(deftest ^{:stratum 1} current-revocation-and-expiry-deny-test
  (doseq [reason [:revoked :expired :scope :count]]
    (let [dir (fixture/tmp-dir)
          {g :grant t :transaction} (fixture/merge-case! dir)
          grants (atom {(:grant/id g) g})
          lookup (partial current-grant grants)
          clock (if (= :expired reason) fixture/much-later fixture/now)
          received (atom [])]
      (case reason
        :revoked (swap! grants update (:grant/id g) grant/revoke :revocation/operator fixture/now)
        :scope (swap! grants assoc-in [(:grant/id g) :grant/scope :pr/number] 99)
        :count (swap! grants assoc-in [(:grant/id g) :grant/constraints :constraint/max-count] 0)
        :expired nil)
      (let [done (fx/commit-current! dir (:effect/id t) lookup
                                   (constantly clock) (partial capture-success! received))]
        (is (= :failed (:effect/state done)) (name reason))
        (is (empty? @received))
        (is (= done (fx/read-record dir (:effect/id t))))))))

(deftest ^{:stratum 1} invalid-authority-never-claims-test
  (doseq [value [nil {} :authority/unenforced]]
    (let [dir (fixture/tmp-dir)
          {t :transaction} (fixture/merge-case! dir)
          received (atom [])
          result (fx/commit-current! dir (:effect/id t) (constantly value)
                                    (constantly fixture/now) (partial capture-success! received))]
      (is (anomaly/anomaly? result))
      (is (empty? @received))
      (is (= t (fx/read-record dir (:effect/id t)))))))

(deftest ^{:stratum 1} unavailable-runtime-ports-preserve-intent-test
  (let [unavailable (anomaly/anomaly :unavailable "offline" {})]
    (doseq [lookup-fails? [true false]
            failed-port [throw-port! (constantly unavailable) (constantly nil)]]
      (let [dir (fixture/tmp-dir)
            {g :grant t :transaction} (fixture/merge-case! dir)
            lookup (if lookup-fails? failed-port (constantly g))
            clock (if lookup-fails? (constantly fixture/now) failed-port)
            received (atom [])
            result (fx/commit-current! dir (:effect/id t) lookup clock
                                      (partial capture-success! received))]
        (is (anomaly/anomaly? result))
        (is (empty? @received))
        (is (= t (fx/read-record dir (:effect/id t))))))))

(deftest ^{:stratum 1} wrong-authority-identity-or-class-is-refused-test
  (doseq [field [:grant/id :grant/effect-class]]
    (let [dir (fixture/tmp-dir)
          {g :grant t :transaction} (fixture/merge-case! dir)
          value (if (= field :grant/id) (random-uuid) :effect/deploy)
          other (assoc g field value)
          received (atom [])
          result (fx/commit-current! dir (:effect/id t) (constantly other)
                                    (constantly fixture/now) (partial capture-success! received))]
      (is (= :conflict (:anomaly/type result)))
      (is (empty? @received))
      (is (= t (fx/read-record dir (:effect/id t)))))))

(deftest ^{:stratum 1} duplicate-attempt-does-not-reexecute-test
  (let [dir (fixture/tmp-dir)
        {g :grant t :transaction} (fixture/merge-case! dir)
        received (atom [])
        commit! (partial fx/commit-current! dir (:effect/id t) (constantly g)
                         (constantly fixture/now) (partial capture-success! received))]
    (is (= :succeeded (:effect/state (commit!))))
    (is (anomaly/anomaly? (commit!)))
    (is (= 1 (count @received)))))

(deftest ^{:stratum 1} claim-race-refuses-the-losing-consumer-test
  (let [dir (fixture/tmp-dir)
        {g :grant t :transaction} (fixture/merge-case! dir)
        received (atom [])
        lookup (partial competing-commit! dir t g)
        result (fx/commit-current! dir (:effect/id t) lookup
                                  (constantly fixture/now) (partial capture-success! received))]
    (is (= :conflict (:anomaly/type result)))
    (is (empty? @received))
    (is (= :succeeded (:effect/state (fx/read-record dir (:effect/id t)))))))

(deftest ^{:stratum 1} uncertain-effect-stays-unknown-test
  (doseq [effect! [throw-effect! (constantly nil) (constantly {:unexpected true})]]
    (let [dir (fixture/tmp-dir)
          {g :grant t :transaction} (fixture/merge-case! dir)
          commit! (partial fx/commit-current! dir (:effect/id t) (constantly g)
                           (constantly fixture/now) effect!)
          done (commit!)]
      (is (= :unknown-outcome (:effect/state done)))
      (is (= done (fx/read-record dir (:effect/id t))))
      (is (anomaly/anomaly? (commit!))))))

(deftest ^{:stratum 1} invalid-boundary-inputs-do-not-call-runtime-test
  (doseq [args [[nil (random-uuid)] ["" (random-uuid)] [" " (random-uuid)]
               [(File. "") (random-uuid)]
               [(fixture/tmp-dir) nil] [(fixture/tmp-dir) {:effect/id (random-uuid)}]]]
    (testing (pr-str args)
      (is (= :invalid-input
             (:anomaly/type (apply fx/commit-current! (concat args [throw-port! throw-port! throw-port!]))))))))

(deftest ^{:stratum 1} absent-effect-does-not-read-runtime-test
  (let [result (fx/commit-current! (fixture/tmp-dir) (random-uuid)
                                  throw-port! throw-port! throw-port!)]
    (is (= :anomalies.effect-transaction/not-found (:anomaly/subtype result)))))

(deftest ^{:stratum 1} delegated-grants-fail-closed-test
  (let [dir (fixture/tmp-dir)
        {g :grant t :transaction} (fixture/merge-case! dir)
        delegated (assoc g :grant/parent-id (random-uuid))
        received (atom [])
        result (fx/commit-current! dir (:effect/id t) (constantly delegated)
                                  (constantly fixture/now) (partial capture-success! received))]
    (is (= :failed (:effect/state result)))
    (is (empty? @received))))

(comment
  (clojure.test/run-tests 'ai.miniforge.effect-transaction.current-commit-test))
