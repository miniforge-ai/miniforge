;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.home-paths-test
  "With no directory in the context, deploy effect records and grant
   breaches go under the miniforge home (MINIFORGE_HOME, else ~/.miniforge)."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.phase-deployment.deploy-authority :as authority]
            [ai.miniforge.phase-deployment.deploy-transaction :as transaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} default-dirs-follow-miniforge-home
  (with-redefs [config/miniforge-home (constantly "/mf-home")]
    (is (= "/mf-home/effects" (transaction/store-dir {})))
    (is (= "/mf-home/grant-breaches" (authority/breach-dir {})))
    (is (= "/chosen" (transaction/store-dir {:effect-store-dir "/chosen"})))))

(comment ::home-paths)
