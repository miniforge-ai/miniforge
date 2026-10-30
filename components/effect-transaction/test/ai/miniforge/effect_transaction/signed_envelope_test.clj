;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.signed-envelope-test
  "Commit refuses a proposal whose allowing envelope the runtime did not
   sign: missing, naming another id, refusing, or altered after signing."
  (:require
   [ai.miniforge.effect-transaction.fixtures :as fixture]
   [ai.miniforge.effect-transaction.interface :as fx]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} allowing {:envelope/id #uuid "00000000-0000-0000-0000-0000000000e1"
                             :envelope/decision :allow})

(defn- ^{:stratum 0} propose-with!
  "Propose the effect `g` authorizes, naming `envelope-id` and carrying `envelope`."
  [dir g envelope-id envelope]
  (fx/propose! dir (cond-> {:effect-id (get-in g [:grant/scope :effect/id])
                            :effect-class :effect/merge
                            :grant-id (:grant/id g)
                            :envelope-id envelope-id
                            :proposal fixture/merge-proposal}
                     envelope (assoc :envelope envelope))
               fixture/now))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} an-unsigned-or-refusing-envelope-never-runs-the-effect-test
  (doseq [[label envelope-id envelope]
          [[:missing (:envelope/id allowing) nil]
           [:unsigned (:envelope/id allowing) allowing]
           [:other-id (random-uuid) (fx/sign-envelope allowing)]
           [:refusing (:envelope/id allowing) (fx/sign-envelope (assoc allowing :envelope/decision :deny))]
           [:altered (:envelope/id allowing)
            (assoc (fx/sign-envelope (assoc allowing :envelope/decision :deny)) :envelope/decision :allow)]]]
    (testing (name label)
      (let [dir (fixture/tmp-dir)
            g (fixture/merge-grant)
            t (propose-with! dir g envelope-id envelope)
            calls (atom 0)
            done (fx/commit-current! dir (:effect/id t) (constantly g) (constantly fixture/now)
                                     (fn [_] (swap! calls inc) {:effect/outcome :succeeded}))]
        (is (= :failed (:effect/state done)))
        (is (zero? @calls))))))

(deftest ^{:stratum 1} a-signed-allowing-envelope-survives-the-store-test
  (let [dir (fixture/tmp-dir)
        g (fixture/merge-grant)
        envelope (fx/sign-envelope (assoc allowing :envelope/at fixture/now))
        t (propose-with! dir g (:envelope/id envelope) envelope)]
    (is (fx/envelope-signed? (:effect/envelope (fx/read-record dir (:effect/id t))))
        "the envelope's instant round-trips the store without breaking its signature")))
