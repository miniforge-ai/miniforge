(ns ai.miniforge.cli.app-config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.resource-config :as resource-config]))

(deftest default-app-profile-test
  (let [profile (app-config/app-profile)]
    (testing "base CLI defaults to the flagship profile"
      (is (= "miniforge" (:name profile)))
      (is (= ".miniforge" (:home-dir-name profile)))
      (is (= "miniforge-tui" (:tui-package profile))))
    (testing "derived paths use the configured home dir"
      (is (.endsWith (app-config/config-path) "/.miniforge/config.edn"))
      (is (.endsWith (app-config/events-dir) "/.miniforge/events")))))

(deftest app-profile-loads-resource-backed-config-test
  (testing "app profile delegates to the resource-backed config loader"
    (with-redefs [resource-config/merged-resource-config
                  (fn [_resource _key _default]
                    {:name "workflow-kernel"
                     :help-examples '("run demo" "doctor")})]
      (let [profile (app-config/app-profile)]
        (is (= "workflow-kernel" (:name profile)))
        (is (= ["run demo" "doctor"] (:help-examples profile)))))))
