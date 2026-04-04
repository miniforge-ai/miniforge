;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.cli.app-config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.resource-config :as resource-config]))

(deftest default-app-profile-test
  (let [profile (app-config/app-profile)]
    (testing "base CLI exposes a resource-backed profile"
      (is (= (:name profile) (app-config/binary-name)))
      (is (= (:display-name profile) (app-config/display-name)))
      (is (= (:description profile) (app-config/description)))
      (is (= (:tui-package profile) (app-config/tui-package))))
    (testing "derived paths use the configured home dir"
      (is (.endsWith (app-config/config-path)
                     (str "/" (:home-dir-name profile) "/config.edn")))
      (is (.endsWith (app-config/events-dir)
                     (str "/" (:home-dir-name profile) "/events"))))))

(deftest app-profile-loads-resource-backed-config-test
  (testing "app profile delegates to the resource-backed config loader"
    (with-redefs [resource-config/merged-resource-config
                  (fn [_resource _key _default]
                    {:name "miniforge-core"
                     :help-examples '("run demo" "doctor")})]
      (let [profile (app-config/app-profile)]
        (is (= "miniforge-core" (:name profile)))
        (is (= ["run demo" "doctor"] (:help-examples profile)))))))
