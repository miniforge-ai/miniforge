;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.miniforge-home-paths-test
  "With MINIFORGE_HOME set, the workflow's state paths resolve under it:
   the shipped config defaults name no root of their own."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.persistence :as persistence]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} under? [home path]
  (fs/starts-with? (fs/canonicalize path) (fs/canonicalize home)))

(defn- ^{:stratum 0} call-with-miniforge-home
  "Call `f` with a temp dir standing in for MINIFORGE_HOME: the home and
   the user config file move there; the shipped defaults still apply."
  [f]
  (fs/with-temp-dir [home {:prefix "mf-home-"}]
    (let [home (str home)
          load config/load-merged-config]
      (with-redefs [config/miniforge-home (constantly home)
                    config/load-merged-config (fn ([] (load (str home "/config.edn")))
                                                ([path] (load path)))]
        (f home)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} state-paths-follow-miniforge-home
  (call-with-miniforge-home
   (fn [home]
     (is (= (str home "/checkpoints") (paths/resolve-checkpoint-root {})))
     (is (under? home (persistence/active-config-path)))
     (is (under? home (persistence/ensure-config-dir))))))

(comment ::miniforge-home-paths)
