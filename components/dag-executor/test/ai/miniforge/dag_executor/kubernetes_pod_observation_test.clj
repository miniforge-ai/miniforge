;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-pod-observation-test
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-pod-observation :as observation]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.kubernetes-creation-fixtures :as creation]
            [ai.miniforge.dag-executor.kubernetes-pod-fixtures :as pods]
            [ai.miniforge.kubernetes-target.interface :as target]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} retained
  {:job-name "capsule"
   :job-uid "original-job"
   :namespace "capsules"})

(def ^{:stratum 0 :private true} candidate {:pod-name "candidate"})

(def ^{:stratum 0 :private true} observation-timeout-ms 6500)

(defn- ^{:stratum 0} refuse-io! [& _] (throw (AssertionError.)))

(defn- ^{:stratum 0} interrupt-with-reply! [reply & _]
  (.interrupt (Thread/currentThread))
  reply)

(defn- ^{:stratum 0} expired? [state _] @state)

(defn- ^{:stratum 0} validate-and-expire! [owns? expired job pod]
  (let [owned? (owns? job pod)]
    (reset! expired true)
    owned?))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} response [change]
  (pods/reply (change (pods/pod retained (:pod-name candidate)))))

(defn- ^{:stratum 1} inspect-readback! [reply until arguments]
  (is (pos? (deadline/remaining-ns until)))
  (is (some #{(str "--request-timeout=" observation-timeout-ms "ms")} arguments))
  (is (= ["--" (:pod-name candidate)] (take-last 2 arguments)))
  reply)

(deftest ^{:stratum 1} test-preexisting-cancellation-never-dispatches-readback
  (with-redefs [command/run! refuse-io!]
    (try
      (.interrupt (Thread/currentThread))
      (let [failure (observation/verify! nil retained observation-timeout-ms candidate)
            interrupted? (Thread/interrupted)]
        (is (= :pod-owner-unconfirmed (get-in failure [:error :code])))
        (is interrupted?))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 1} test-interrupted-readback-preserves-cancellation
  (with-redefs [command/run! (partial interrupt-with-reply! (creation/provider-reply 130 ""))]
    (try
      (let [failure (observation/verify! nil retained observation-timeout-ms candidate)
            interrupted? (Thread/interrupted)]
        (is (= :pod-owner-unconfirmed (get-in failure [:error :code])))
        (is interrupted?))
      (finally (Thread/interrupted)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-exact-observed-pod-identity-is-retained
  (with-redefs [command/run! (partial inspect-readback! (response identity))]
    (is (= (assoc candidate :pod-uid "observed-pod-uid"
                           :pod-observed-resource-version "observed-pod-version")
           (:data (observation/verify! nil retained observation-timeout-ms candidate))))))

(deftest ^{:stratum 2} test-name-and-status-cannot-substitute-for-current-ownership
  (doseq [change [#(assoc-in % ["metadata" "ownerReferences" 0 "uid"] "replacement-job")
                  #(assoc-in % ["metadata" "ownerReferences" 0 "controller"] false)
                  #(assoc-in % ["metadata" "namespace"] "another-namespace")
                  #(assoc-in % ["metadata" "name"] "another-pod")
                  #(assoc-in % ["metadata" "deletionTimestamp"] "2026-11-15T00:00:00Z")
                  #(update % "metadata" dissoc "uid")
                  #(update % "metadata" dissoc "resourceVersion")
                  #(assoc % "apiVersion" "v2")
                  #(assoc % "kind" "Job")
                  #(assoc-in % ["status" "phase"] "Failed")]]
    (with-redefs [command/run! (constantly (response change))]
      (is (= :pod-owner-unconfirmed
             (get-in (observation/verify! nil retained observation-timeout-ms candidate) [:error :code]))))))

(deftest ^{:stratum 2} test-late-identity-validation-does-not-admit-the-pod
  (let [expired (atom false)
        owns? target/owns?]
    (with-redefs [command/run! (constantly (response identity))
                  deadline/expired? (partial expired? expired)
                  target/owns? (partial validate-and-expire! owns? expired)]
      (is (= :pod-owner-unconfirmed
             (get-in (observation/verify! nil retained observation-timeout-ms candidate) [:error :code]))))))

(deftest ^{:stratum 2} test-cancellation-fences-even-a-successful-owned-pod-reply
  (with-redefs [command/run! (partial interrupt-with-reply! (response identity))]
    (try
      (let [failure (observation/verify! nil retained observation-timeout-ms candidate)
            interrupted? (Thread/interrupted)]
        (is (= :pod-owner-unconfirmed (get-in failure [:error :code])))
        (is interrupted?))
      (finally (Thread/interrupted)))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-pod-observation-test))
