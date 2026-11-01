;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.home-agreement-test
  "MINIFORGE_HOME set to \"\" is unset for the CLI and for core alike, so
   `mf resume` reads events and checkpoints where runs record them."
  (:require [ai.miniforge.cli.app-config :as app-config]
            [ai.miniforge.cli.app-config.profile :as profile]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} an-empty-miniforge-home-is-unset-for-cli-and-core
  (let [override config/miniforge-home-override
        load config/load-merged-config]
    (fs/with-temp-dir [user-config-dir {:prefix "mf-user-config-"}]
      (with-redefs [profile/getenv (fn [var-name] (when (= "MINIFORGE_HOME" var-name) ""))
                    config/miniforge-home-override (fn ([] (override "")) ([v] (override v)))
                    ;; No user config.edn naming a root of its own.
                    config/load-merged-config (fn ([] (load (str (fs/path user-config-dir "config.edn"))))
                                                ([path] (load path)))]
        (is (= (config/miniforge-home) (app-config/home-dir)))
        (is (= (str (es/default-events-dir)) (app-config/events-dir)))
        (is (= (str (app-config/home-dir) "/checkpoints")
               (checkpoint-paths/resolve-checkpoint-root {})))))))

(comment ::home-agreement)
