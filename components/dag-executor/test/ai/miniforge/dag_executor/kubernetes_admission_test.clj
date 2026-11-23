;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-admission-test
  "Exercise actual acquisition, readiness and ownership; substitute only native I/O."
  (:require [ai.miniforge.dag-executor.protocols.executor :as executor]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes :as kubernetes]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.kubernetes-creation-fixtures :as creation]
            [ai.miniforge.dag-executor.kubernetes-pod-fixtures :as pods]
            [ai.miniforge.dag-executor.kubernetes-native-fixtures :as native]
            [ai.miniforge.dag-executor.result :as result]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} runtime []
  (let [state (atom nil)
        commands (atom [])
        queries (atom [])
        instance (kubernetes/create-kubernetes-executor {:namespace "capsules"})]
    {:fixture/state state
     :fixture/commands commands
     :fixture/queries queries
     :fixture/executor instance}))

(defn- ^{:stratum 0} submit! [runtime & arguments]
  (swap! (:fixture/commands runtime) conj (second (second arguments)))
  (apply creation/response identity arguments))

(defn- ^{:stratum 0} legacy-command! [runtime & arguments]
  (swap! (:fixture/commands runtime) conj (second arguments))
  (throw (AssertionError.)))

(defn- ^{:stratum 0} query! [runtime read! state until arguments]
  (swap! (:fixture/queries runtime) conj arguments)
  (read! state until arguments))

(defn- ^{:stratum 0} cached [runtime]
  @(:pod-cache (:fixture/executor runtime)))

(defn- ^{:stratum 0} changed-owner [change state until arguments]
  (if (pods/structured-query? arguments)
    (pods/reply (change @state))
    (pods/read-pod! state until arguments)))

(defn- ^{:stratum 0} failed-readiness [state until arguments]
  (let [status? (and (= "pod" (nth arguments 2 nil))
                     (not (pods/structured-query? arguments)))]
    (if status? (creation/provider-reply 0 "Failed") (pods/read-pod! state until arguments))))

(defn- ^{:stratum 0} cancelled-owner-read [state until arguments]
  (let [reply (pods/read-pod! state until arguments)]
    (when (pods/structured-query? arguments) (.interrupt (Thread/currentThread)))
    reply))

(defn- ^{:stratum 0} mark-owned-read! [seen state until arguments]
  (let [reply (pods/read-pod! state until arguments)]
    (when (pods/structured-query? arguments) (reset! seen true))
    reply))

(defn- ^{:stratum 0} interrupt-clock! []
  (.interrupt (Thread/currentThread))
  false)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} cancel-after-owned-read! [seen clock until]
  (if @seen (interrupt-clock!) (clock until)))

(defn- ^{:stratum 1} acquire! [runtime read!]
  (with-redefs [shell/sh (partial legacy-command! runtime)
                command/run! (partial native/dispatch! (:fixture/state runtime)
                                      (partial submit! runtime) (partial query! runtime read!))]
    (executor/acquire-environment! (:fixture/executor runtime) (random-uuid) {})))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-confirmed-job-and-pod-reach-the-environment-and-cache
  (let [runtime (runtime)
        acquired (acquire! runtime pods/read-pod!)]
    (is (result/ok? acquired))
    (is (= "observed-pod-uid" (get-in acquired [:data :metadata :pod-uid])))
    (is (= ["create"] @(:fixture/commands runtime)))
    (is (= 3 (count @(:fixture/queries runtime))))
    (is (= 1 (count (cached runtime))))))

(deftest ^{:stratum 2} test-failed-pod-proof-never-caches-or-deletes-by-job-name
  (doseq [change [#(assoc-in % ["metadata" "ownerReferences" 0 "uid"] "replacement-job")
                  #(assoc-in % ["metadata" "namespace"] "another-namespace")
                  #(update % "metadata" dissoc "uid")]
          :let [runtime (runtime)
                acquired (acquire! runtime (partial changed-owner change))]]
    (is (= :pod-owner-unconfirmed (get-in acquired [:error :code])))
    (is (empty? (cached runtime)))
    (is (= ["create"] @(:fixture/commands runtime)))
    (is (= 3 (count @(:fixture/queries runtime))))))

(deftest ^{:stratum 2} test-failed-readiness-never-verifies-retains-or-deletes-by-name
  (let [runtime (runtime)
        acquired (acquire! runtime failed-readiness)]
    (is (= :pod-failed (get-in acquired [:error :code])))
    (is (empty? (cached runtime)))
    (is (= ["create"] @(:fixture/commands runtime)))
    (is (= 2 (count @(:fixture/queries runtime))))))

(deftest ^{:stratum 2} test-preexisting-cancellation-never-dispatches-or-caches
  (let [runtime (runtime)]
    (try
      (.interrupt (Thread/currentThread))
      (let [acquired (acquire! runtime pods/read-pod!)
            interrupted? (Thread/interrupted)]
        (is (= :pod-interrupted (get-in acquired [:error :code])))
        (is interrupted?)
        (is (empty? (cached runtime)))
        (is (empty? @(:fixture/commands runtime)))
        (is (empty? @(:fixture/queries runtime))))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 2} test-cancelled-successful-owner-read-never-caches-or-cleans-by-name
  (let [runtime (runtime)]
    (try
      (let [acquired (acquire! runtime cancelled-owner-read)
            interrupted? (Thread/interrupted)]
        (is (= :pod-interrupted (get-in acquired [:error :code])))
        (is interrupted?)
        (is (empty? (cached runtime)))
        (is (= ["create"] @(:fixture/commands runtime)))
        (is (= 3 (count @(:fixture/queries runtime)))))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 2} test-cancellation-after-identity-validation-keeps-the-cache-empty
  (let [runtime (runtime)
        seen (atom false)
        clock deadline/expired?]
    (try
      (with-redefs [deadline/expired? (partial cancel-after-owned-read! seen clock)]
        (let [acquired (acquire! runtime (partial mark-owned-read! seen))
              interrupted? (Thread/interrupted)]
          (is (= :pod-interrupted (get-in acquired [:error :code])))
          (is interrupted?)
          (is (empty? (cached runtime)))
          (is (= ["create"] @(:fixture/commands runtime)))
          (is (= 3 (count @(:fixture/queries runtime))))))
      (finally (Thread/interrupted)))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-admission-test))
