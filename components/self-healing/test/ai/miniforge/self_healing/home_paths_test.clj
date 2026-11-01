;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.self-healing.home-paths-test
  "With MINIFORGE_HOME set, self-healing's state files live under it."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.self-healing.backend-health :as backend-health]
            [ai.miniforge.self-healing.workaround-registry :as registry]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} state-files-follow-miniforge-home
  (fs/with-temp-dir [home {:prefix "mf-home-"}]
    (let [home (str home)]
      (with-redefs [config/miniforge-home (constantly home)]
        (is (= (str home "/backend_health.edn") (backend-health/backend-health-path)))
        (is (= (str home "/known_workarounds.edn") (registry/workaround-registry-path)))))))

(comment ::home-paths)
