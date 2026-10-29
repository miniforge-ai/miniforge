;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-pin-test
  "A pinned checkpoint (`:pinned? true` in its manifest) stays pinned
   across the run's later checkpoints, and the checkpoint GC keeps it."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-ctx [root opts]
  (context/create-context {:workflow/id :pin :workflow/version "1.0.0"
                           :workflow/pipeline [{:phase :plan}]}
                          {:task "Test"}
                          (assoc opts :checkpoint/root root)))

(defn- ^{:stratum 0} manifest [root ctx]
  (edn/read-string (slurp (paths/manifest-path root (:execution/id ctx)))))

(defn- ^{:stratum 0} age-run! [root ctx days]
  (let [at (- (System/currentTimeMillis) (* days 24 60 60 1000))]
    (doseq [f (reverse (file-seq (fs/file root (str (:execution/id ctx)))))]
      (.setLastModified ^java.io.File f at))))

(deftest ^{:stratum 0} pinning-a-run-without-a-manifest-is-not-found
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [result (workflow/pin-checkpoint! (random-uuid) {:checkpoint/root root})]
       (testing "nothing is created"
         (is (anomaly/anomaly? result))
         (is (= :not-found (:anomaly/type result))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-pin-set-on-the-manifest-survives-later-checkpoints
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [ctx (run-ctx root {})
           path (paths/manifest-path root (:execution/id ctx))]
       (store/persist-execution-state! ctx)
       (spit path (pr-str (assoc (manifest root ctx) :pinned? true)))
       (store/persist-execution-state! (assoc ctx :execution/redirect-count 1))
       (is (true? (:pinned? (manifest root ctx))))))))

(deftest ^{:stratum 1} pin-checkpoint-pins-and-gc-keeps-the-run
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [ctx (run-ctx root {})
           opts {:checkpoint/root root}]
       (store/persist-execution-state! ctx)
       (is (string? (workflow/pin-checkpoint! (:execution/id ctx) opts)))
       (is (true? (:pinned? (manifest root ctx))))
       (age-run! root ctx 90)
       (let [result (workflow/gc-checkpoints! opts)]
         (is (empty? (:deleted result)))
         (is (= [(:execution/id ctx)] (get-in result [:kept :pinned]))))))))

(deftest ^{:stratum 1} a-run-started-pinned-writes-a-pinned-manifest
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [ctx (run-ctx root {:checkpoint/pin? true})]
       (store/persist-execution-state! ctx)
       (is (true? (:pinned? (manifest root ctx))))))))

(comment ::checkpoint-pin)
