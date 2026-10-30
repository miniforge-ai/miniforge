;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.attempt-test
  (:require
   [ai.miniforge.effect-transaction.fixtures :as fixture]
   [ai.miniforge.effect-transaction.interface :as fx]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} basis [:effect/merge #uuid "00000000-0000-0000-0000-00000000000a" "o/r" 7])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} propose-attempt! [dir n]
  (fx/propose! dir {:effect-id (fx/attempt-id basis n) :effect-class :effect/merge
                    :grant-id (random-uuid) :envelope-id (random-uuid)
                    :proposal fixture/merge-proposal}
               fixture/now))

(deftest ^{:stratum 1} attempt-ids-are-fixed-by-basis-and-attempt-test
  (is (= (fx/attempt-id basis 0) (fx/attempt-id basis 0)))
  (is (not= (fx/attempt-id basis 0) (fx/attempt-id basis 1)))
  (is (not= (fx/attempt-id basis 0) (fx/attempt-id (assoc basis 3 8) 0))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} open-attempt-skips-only-definite-failures-test
  (testing "given no records → attempt 0"
    (is (= {:effect/id (fx/attempt-id basis 0)} (fx/open-attempt (fixture/tmp-dir) basis))))
  (testing "given attempt 0 in flight → that record, not a new id"
    (let [dir (fixture/tmp-dir)
          t (propose-attempt! dir 0)]
      (is (= (:effect/id t) (get-in (fx/open-attempt dir basis) [:effect/existing :effect/id])))))
  (testing "given attempt 0 failed → attempt 1"
    (let [dir (fixture/tmp-dir)
          t (propose-attempt! dir 0)]
      (fx/commit! dir t :authority/unenforced {} fixture/now
                  (fn [] {:effect/outcome :failed :effect/failure "refused"}))
      (is (= {:effect/id (fx/attempt-id basis 1)} (fx/open-attempt dir basis))))))
