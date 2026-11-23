;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.opsv-commit-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as fx]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as fixtures]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} proposal [g scope]
  (let [payload (dissoc scope :effect/id)]
    {:effect-id (:effect/id scope)
     :effect-class (:grant/effect-class g)
     :grant-id (:grant/id g)
     :envelope-id (random-uuid)
     :proposal payload}))

(defn ^{:stratum 0} success [] {:effect/outcome :succeeded})

(defn ^{:stratum 0} result [dir t done calls]
  (let [stored (fx/read-record dir (:effect/id t))]
    {:transaction t
     :result done
     :stored stored
     :calls @calls}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} current-effect! [dir calls t]
  (swap! calls conj [t (fx/read-record dir (:effect/id t))])
  (success))

(defn- ^{:stratum 1} legacy-effect! [calls]
  (swap! calls conj :legacy)
  (success))

(defn ^{:stratum 1} proposed! [dir g scope]
  (grant/register! dir g)
  (fx/propose! dir (proposal g scope) fixtures/now))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} current! [dir t clock]
  (let [calls (atom [])
        done (fx/commit-current! dir (:effect/id t) (partial grant/current dir)
                                 (constantly clock) (partial current-effect! dir calls))]
    (result dir t done calls)))

(defn ^{:stratum 2} legacy!
  ([dir t] (legacy! dir t :authority/unenforced))
  ([dir t authority]
   (let [calls (atom [])
         done (fx/commit! dir t authority {} fixtures/now (partial legacy-effect! calls))]
     (result dir t done calls))))

(comment ::only-provider-effects-are-simulated-grants-and-transactions-use-real-stores)
