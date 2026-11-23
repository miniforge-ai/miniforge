;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.miniforge-home-paths-test
  "With MINIFORGE_HOME set, the workflow's state paths resolve under it:
   the shipped config defaults name no root of their own."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.persistence :as persistence]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} under? [home path]
  (fs/starts-with? (fs/canonicalize path) (fs/canonicalize home)))

(defn- ^{:stratum 0} call-with-miniforge-home
  "Call `f` with a temp dir standing in for MINIFORGE_HOME: the home and
   the user config file move there; the shipped defaults still apply.
   `user-config` stands in for that config.edn."
  ([f] (call-with-miniforge-home {} f))
  ([user-config f]
   (fs/with-temp-dir [home {:prefix "mf-home-"}]
     (let [home (str home)
           load config/load-merged-config]
       (with-redefs [config/miniforge-home (constantly home)
                     config/miniforge-home-override (constantly home)
                     config/load-merged-config (fn ([] (merge (load (str home "/config.edn")) user-config))
                                                 ([path] (load path)))]
         (f home))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} gc-recording-deletes
  "Run checkpoint GC with `opts`; deletion only records its target, and
   deletes nothing outside `home`. Returns [result deleted-paths]."
  [home opts]
  (let [deleted (atom [])
        delete-tree fs/delete-tree]
    [(with-redefs [fs/delete-tree (fn [path & args]
                                    (swap! deleted conj (str path))
                                    (when (under? home path) (apply delete-tree path args)))]
       (workflow/gc-checkpoints! opts))
     @deleted]))

(deftest ^{:stratum 1} state-paths-follow-miniforge-home
  (call-with-miniforge-home
   (fn [home]
     (is (= (str home "/checkpoints") (paths/resolve-checkpoint-root {})))
     (is (under? home (persistence/active-config-path)))
     (is (under? home (persistence/ensure-config-dir))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} checkpoint-gc-stays-inside-miniforge-home
  (call-with-miniforge-home
   (fn [home]
     (let [id (fixture/expired-run! (str home "/checkpoints"))
           [result deleted] (gc-recording-deletes home {})]
       (is (= [id] (:deleted result)))
       (is (seq deleted))
       (is (every? #(under? home %) deleted) (str "deleted outside " home ": " deleted))))))

(deftest ^{:stratum 2} checkpoint-gc-refuses-a-configured-root-outside-miniforge-home
  (fs/with-temp-dir [elsewhere {:prefix "mf-elsewhere-"}]
    (let [elsewhere (str elsewhere)
          id (fixture/expired-run! elsewhere)]
      (call-with-miniforge-home
       {:workflow {:checkpoint-root elsewhere}}
       (fn [home]
         (testing "from config: refused, nothing deleted"
           (let [[result deleted] (gc-recording-deletes home {})]
             (is (anomaly/anomaly? result))
             (is (= :conflict (:anomaly/type result)))
             (is (empty? deleted))
             (is (fs/exists? (fs/file elsewhere (str id))))))
         (testing "named explicitly: collected"
           (let [result (workflow/gc-checkpoints! {:checkpoint/root elsewhere})]
             (is (= [id] (:deleted result))))))))))

(deftest ^{:stratum 2} a-named-root-that-did-not-resolve-is-no-escape
  ;; :execution/checkpoint-root takes precedence over :checkpoint/root:
  ;; naming a root inside the home must not let GC collect one outside it.
  (fs/with-temp-dir [elsewhere {:prefix "mf-elsewhere-"}]
    (let [elsewhere (str elsewhere)
          id (fixture/expired-run! elsewhere)]
      (call-with-miniforge-home
       (fn [home]
         (let [[result deleted] (gc-recording-deletes home {:execution/checkpoint-root elsewhere
                                                             :checkpoint/root (str home "/checkpoints")})]
           (is (anomaly/anomaly? result))
           (is (= :conflict (:anomaly/type result)))
           (is (empty? deleted))
           (is (fs/exists? (fs/file elsewhere (str id))))))))))

(comment ::miniforge-home-paths)
