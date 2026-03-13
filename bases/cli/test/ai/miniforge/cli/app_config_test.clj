(ns ai.miniforge.cli.app-config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]))

(deftest default-app-profile-test
  (let [profile (app-config/app-profile)]
    (testing "base CLI defaults to the flagship profile"
      (is (= "miniforge" (:name profile)))
      (is (= ".miniforge" (:home-dir-name profile)))
      (is (= "miniforge-tui" (:tui-package profile))))
    (testing "derived paths use the configured home dir"
      (is (.endsWith (app-config/config-path) "/.miniforge/config.edn"))
      (is (.endsWith (app-config/events-dir) "/.miniforge/events")))))
