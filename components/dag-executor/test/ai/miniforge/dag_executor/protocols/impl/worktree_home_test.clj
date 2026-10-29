;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.worktree-home-test
  "With MINIFORGE_HOME set, worktrees and task archives go under it, with
   no config and with the shipped config defaults."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} worktree-paths-follow-miniforge-home
  (fs/with-temp-dir [home {:prefix "mf-home-"}]
    (let [home (str home)
          load config/load-merged-config]
      (with-redefs [config/miniforge-home (constantly home)]
        (let [shipped (load (str home "/config.edn"))]
          (is (= (str home "/worktrees") (worktree/default-base-path)))
          (is (= (str home "/worktrees") (worktree/default-base-path {})))
          (is (= (str home "/worktrees") (worktree/default-base-path shipped)))
          (is (= (str home "/checkpoints") (worktree/default-archive-dir))))))))

(comment ::worktree-home)
