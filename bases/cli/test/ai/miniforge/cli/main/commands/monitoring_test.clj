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

(ns ai.miniforge.cli.main.commands.monitoring-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.main.commands.monitoring :as sut]
   [ai.miniforge.cli.main.display :as display]))

(defn- exit-ex
  [code]
  (ex-info "exit" {:code code}))

(deftest tui-cmd-unavailable-uses-message-catalog-test
  (testing "TUI unavailable output reads user-facing copy from the message catalog"
    (binding [sut/*tui-available?* false]
      (with-redefs [messages/t (fn
                                 ([k]
                                  (case k
                                    :tui/not-available "TUI-NOT-AVAILABLE"
                                    :tui/requires-jvm "TUI-REQUIRES-JVM"
                                    :tui/install-package "TUI-INSTALL"
                                    :tui/use-web "TUI-USE-WEB"
                                    (str "UNEXPECTED:" k)))
                                 ([k params]
                                  (case k
                                    :tui/no-standalone-package (str "NO-STANDALONE:" (:command params))
                                    (str "UNEXPECTED:" k))))
                    display/print-error println
                    app-config/tui-package (constantly "engine-tui")
                    app-config/command-string identity
                    sut/exit! (fn [code] (throw (exit-ex code)))]
        (let [output (with-out-str
                       (try
                         (sut/tui-cmd {})
                         (catch clojure.lang.ExceptionInfo e
                           (is (= 1 (:code (ex-data e)))))))]
          (is (.contains output "TUI-NOT-AVAILABLE"))
          (is (.contains output "TUI-REQUIRES-JVM"))
          (is (.contains output "TUI-INSTALL"))
          (is (.contains output "TUI-USE-WEB")))))))
