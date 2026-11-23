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
(ns ai.miniforge.cli.main-web-port-test
  "`mf web` binds the port from --port, else the config's
   [:dashboard :port], else 7878. Driven through `-main` so the dispatch
   table's option spec is part of what is tested."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.main :as sut]
   [ai.miniforge.cli.main.commands.monitoring :as monitoring]
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} requested-port
  "Run `mf web <args>` with `cfg` as the loaded config. Returns the port
   the dashboard launcher was asked to bind. The fake launcher throws
   after recording the port, so web-cmd takes its start-failed path
   instead of blocking on the browser prompt."
  [cfg & args]
  (let [port (atom nil)
        launcher (fn [opts]
                   (reset! port (:port opts))
                   (throw (ex-info "stop after recording the port" {})))]
    (binding [monitoring/*web-available?* true
              monitoring/*start-web-dashboard!* launcher]
      (with-redefs [config/load-config (constantly cfg)
                    display/print-info (fn [& _] nil)
                    display/print-error (fn [& _] nil)
                    monitoring/exit! (fn [_] nil)]
        (apply sut/-main "web" args)))
    @port))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} web-uses-configured-dashboard-port-test
  (testing "without --port, the config's dashboard port is used"
    (is (= 9191 (requested-port {:dashboard {:port 9191}})))))

(deftest ^{:stratum 1} web-port-flag-overrides-config-test
  (testing "--port wins over the config"
    (is (= 8000 (requested-port {:dashboard {:port 9191}} "--port" "8000")))
    (is (= 8001 (requested-port {:dashboard {:port 9191}} "-p" "8001")))))

(deftest ^{:stratum 1} web-falls-back-to-7878-test
  (testing "with neither --port nor a configured port, 7878 is used"
    (is (= 7878 (requested-port {})))))
