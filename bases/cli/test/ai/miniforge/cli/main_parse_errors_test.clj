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
(ns ai.miniforge.cli.main-parse-errors-test
  "Command-line errors seen through `-main`: an unknown command exits 2,
   and a command group named alone lists its own subcommands instead of
   the global help."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main :as main]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-main
  "Run `-main` with `args` and exit! stubbed. Returns the exit code (nil
   when none), stdout, stderr, and, when something was thrown out of
   -main, its message."
  [& args]
  (let [code (atom nil)
        thrown (atom nil)
        err (java.io.StringWriter.)
        out (with-out-str
              (binding [*err* err]
                (with-redefs [shared/exit! (fn [c] (reset! code c) nil)]
                  (try+
                    (apply main/-main args)
                    (catch Object _
                      (reset! thrown (or (:message &throw-context) "thrown, no message")))))))]
    {:exit @code :out out :err (str err) :thrown @thrown}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} command-groups-list-their-own-subcommands-test
  (testing "a group with no handler of its own"
    (let [{:keys [exit out thrown]} (run-main "fleet")]
      (is (nil? thrown))
      (is (nil? exit))
      (is (.contains out (app-config/command-string "fleet" "watch")))))
  (testing "a group that used to print the global help"
    (let [{:keys [exit out]} (run-main "config")]
      (is (nil? exit))
      (is (.contains out (app-config/command-string "config" "backend"))))))

(deftest ^{:stratum 1} unknown-commands-exit-2-test
  (testing "at the top level, where the global help used to swallow the word"
    (let [{:keys [exit err]} (run-main "nosuch")]
      (is (= 2 exit))
      (is (.contains err "nosuch"))))
  (testing "after a group whose handler prints its help"
    (let [{:keys [exit err]} (run-main "workflow" "nosuch")]
      (is (= 2 exit))
      (is (.contains err "workflow nosuch")))))
