;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.signed-grant-test
  "Commit refuses a grant the runtime did not sign: one written by hand
   into a store, or one whose terms were changed after issue."
  (:require
   [ai.miniforge.effect-transaction.fixtures :as fixture]
   [ai.miniforge.effect-transaction.interface :as fx]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} commit-with
  "Commit `t` under `g` as the current grant; count effect calls."
  [dir t g calls]
  (fx/commit-current! dir (:effect/id t) (constantly g) (constantly fixture/now)
                      (fn [_] (swap! calls inc) {:effect/outcome :succeeded})))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} an-unsigned-or-altered-grant-never-runs-the-effect-test
  (doseq [[label forge] [[:unsigned #(dissoc % :grant/signature)]
                         [:extended #(assoc % :grant/expires-at fixture/much-later)]
                         [:re-signed-elsewhere #(assoc % :grant/signature (apply str (repeat 64 "a")))]]]
    (testing (name label)
      (let [dir (fixture/tmp-dir)
            {g :grant t :transaction} (fixture/merge-case! dir)
            calls (atom 0)
            done (commit-with dir t (forge g) calls)]
        (is (= :failed (:effect/state done)))
        (is (zero? @calls))))))

(deftest ^{:stratum 1} a-runtime-signed-grant-commits-test
  (let [dir (fixture/tmp-dir)
        {g :grant t :transaction} (fixture/merge-case! dir)
        calls (atom 0)]
    (is (= :succeeded (:effect/state (commit-with dir t g calls))))
    (is (= 1 @calls))))
