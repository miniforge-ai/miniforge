;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.provider-dispatch
  "One synchronous provider attempt; callback behavior cannot rewrite its report."
  (:require [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} diagnostics [unconfirmed invalid]
  {:dispatch/unconfirmed-message unconfirmed
   :dispatch/invalid-message invalid})

(defn- ^{:stratum 0} fallback [state messages]
  (if-some [result (:result @state)]
    result
    (let [outcome (if (:invoked? @state) :unknown-outcome :failed)
          failure (:dispatch/unconfirmed-message messages)]
      {:effect/outcome outcome
       :effect/failure failure})))

(defn- ^{:stratum 0} claim [same-thread? state]
  (cond
    (not same-thread?) (assoc state :rejected? true)
    (and (:open? state) (not (:invoked? state))) (assoc state :invoked? true)
    :else state))

(defn- ^{:stratum 0} invalid [messages]
  (anomaly/anomaly :invalid-input (:dispatch/invalid-message messages) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} invoke-once! [owner state operation]
  (let [same-thread? (identical? owner (Thread/currentThread))
        [before _] (swap-vals! state (partial claim same-thread?))]
    (when (and same-thread? (:open? before) (not (:invoked? before)))
      (let [result (operation)] (swap! state assoc :result result) result))))

(defn- ^{:stratum 1} dispatch-result [state messages result]
  (if (or (:invoked? @state) (:rejected? @state)
          (not (or (anomaly/anomaly? result) (= :failed (:effect/outcome result)))))
    (fallback state messages)
    result))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} call-with-exception-handling [dispatch operation messages]
  (let [state (atom {:open? true :invoked? false})
        owner (Thread/currentThread)]
    ;; Java-only callback boundary; this component has no Slingshot dependency.
    (try
      (dispatch-result state messages (dispatch #(invoke-once! owner state operation)))
      (catch clojure.lang.ArityException _
        (if (:invoked? @state) (fallback state messages) (invalid messages)))
      (catch InterruptedException _
        (let [result (fallback state messages)] (.interrupt (Thread/currentThread)) result))
      (catch Throwable _ (fallback state messages))
      (finally (swap! state assoc :open? false)))))

(comment ::dispatch-is-not-grant-authority-durability-or-a-stop-fence)
