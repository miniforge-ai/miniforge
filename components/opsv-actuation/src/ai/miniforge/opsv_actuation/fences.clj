;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.fences
  "Opaque factory-owned handles; weak keys do not retain abandoned runs."
  (:import [java.util Collections WeakHashMap]))

;------------------------------------------------------------------------------ Layer 0

(defonce ^{:private true :stratum 0} states (Collections/synchronizedMap (WeakHashMap.)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} state [fence]
  (.get states fence))

(defn ^{:stratum 1} fence? [value]
  (and (= Object (class value)) (.containsKey states value)))

(defn ^{:stratum 1} create []
  (let [handle (Object.)]
    (.put states handle (atom {:stopped? false :in-flight 0}))
    handle))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} status [fence]
  @(state fence))

(defn ^{:stratum 2} stop! [fence]
  (swap! (state fence) assoc :stopped? true))

(defn ^{:stratum 2} admit! [fence]
  (let [[before _] (swap-vals! (state fence)
                              #(if (:stopped? %) % (update % :in-flight inc)))]
    (not (:stopped? before))))

(defn ^{:stratum 2} release! [fence]
  (swap! (state fence) update :in-flight dec))

(comment
  (status (create)))
