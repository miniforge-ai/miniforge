;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.scratch-gc-home-guard-test
  "Scratch GC refuses to run when MINIFORGE_HOME is set and its queue
   resolves outside it, and touches nothing."
  (:require [ai.miniforge.config.interface :as mf-config]
            [ai.miniforge.dag-executor.result :as result]
            [ai.miniforge.dag-executor.scratch-gc-queue :as sut]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} scratch-gc-refuses-a-queue-outside-miniforge-home
  (fs/with-temp-dir [home {:prefix "mf-home-"}]
    (fs/with-temp-dir [elsewhere {:prefix "mf-elsewhere-"}]
      (let [touched (atom false)]
        (with-redefs [mf-config/miniforge-home-override (constantly (str home))
                      mf-config/miniforge-home (constantly (str elsewhere))
                      sut/prune-stale-queue-entries! (fn [& _] (reset! touched true) (result/ok {}))]
          (let [outcome (sut/run-deferred-gc! (str (fs/cwd)) 0)]
            (is (result/err? outcome))
            (is (= :scratch-gc-queue/outside-home (get-in outcome [:error :code])))
            (is (false? @touched))))))))

(comment ::scratch-gc-home-guard)
