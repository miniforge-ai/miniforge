;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.config.home-test
  "MINIFORGE_HOME is read in one place: a blank value is unset for the
   home, the override and every path built on them."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.config.user :as user]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} a-blank-miniforge-home-is-unset
  (is (nil? (user/miniforge-home-override "")))
  (is (nil? (user/miniforge-home-override "  ")))
  (is (nil? (user/miniforge-home-override nil)))
  (is (= "/mf-home" (user/miniforge-home-override "/mf-home"))))

(deftest ^{:stratum 0} home-and-override-agree
  ;; MINIFORGE_HOME="" : the home is the default, not "" (whose
  ;; config.edn would be /config.edn).
  (let [override user/miniforge-home-override]
    (with-redefs [user/miniforge-home-override (fn ([] (override "")) ([v] (override v)))]
      (is (= (user/default-miniforge-home) (user/miniforge-home)))))
  (with-redefs [user/miniforge-home-override (constantly "/mf-home")]
    (is (= "/mf-home" (user/miniforge-home))))
  (with-redefs [config/miniforge-home-override (constantly nil)]
    (is (= (user/default-miniforge-home) (config/miniforge-home)))))

(comment ::home)
