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
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.main.commands.monitoring :as sut]
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} exit-ex
  [code]
  (ex-info "exit" {:code code}))

(deftest ^{:stratum 0} web-cmd-uses-injected-launcher-test
  (testing "Web startup uses the launcher composed by cli.main"
    (let [started (atom nil)
          exit-code (atom nil)]
      (binding [sut/*web-available?* true
                sut/*start-web-dashboard!* (fn [opts]
                                             (reset! started opts)
                                             (throw (ex-info "stop" {})))]
        (with-redefs [config/load-config (constantly {:dashboard {:port 9191}})
                      messages/t (fn
                                   ([k] (name k))
                                   ([k params] (str (name k) params)))
                      display/print-info (fn [& _] nil)
                      display/print-error (fn [& _] nil)
                      sut/exit! (fn [code] (reset! exit-code code))]
          (sut/web-cmd {}))
        (is (= {:port 9191} @started))
        (is (= 1 @exit-code))))))

(deftest ^{:stratum 0} web-cmd-opens-the-url-the-dashboard-reports-test
  (testing "--open uses the dashboard's own URL, not a rebuilt localhost one"
    (let [opened (atom nil)]
      (binding [sut/*web-available?* true
                sut/*start-web-dashboard!* (fn [_] {:port 9191 :url "http://192.168.1.5:9191"})]
        (with-redefs [config/load-config (constantly {:dashboard {:port 9191}})
                      messages/t (fn ([k] (name k)) ([k _] (name k)))
                      display/print-info (fn [& _] nil)
                      process/sh (fn [& args] (reset! opened (vec args)) {:exit 0})
                      ;; web-cmd parks on @(promise) until interrupted.
                      promise (fn [] (delay nil))]
          (binding [*out* (java.io.StringWriter.)]
            (sut/web-cmd {:open true}))))
      (is (= ["open" "http://192.168.1.5:9191"] @opened)))))

(defn- ^{:stratum 0} web-cmd-with-stdin
  "Run `mf web` (no --open) with stdin holding `input`, stdin a terminal
   or not per `interactive?`, and the dashboard stubbed. Returns the
   process/sh calls (the browser opener) and stdout."
  [input interactive?]
  (let [calls (atom [])
        out   (java.io.StringWriter.)]
    (binding [sut/*web-available?* true
              sut/*start-web-dashboard!* (fn [_] {:port 9191 :url "http://localhost:9191"})]
      (with-redefs [config/load-config (constantly {:dashboard {:port 9191}})
                    display/print-info (fn [& _] nil)
                    sut/interactive? (constantly interactive?)
                    process/sh (fn [& args] (swap! calls conj (vec args)) {:exit 0})
                    ;; web-cmd parks on @(promise) until interrupted.
                    promise (fn [] (delay nil))]
        (binding [*in*  (java.io.BufferedReader. (java.io.StringReader. input))
                  *out* out]
          (sut/web-cmd {}))))
    {:opened @calls :out (str out)}))

(deftest ^{:stratum 0} tui-cmd-uses-injected-launcher-test
  (testing "TUI startup uses the launcher composed by cli.main"
    (let [started (atom nil)
          shut-down? (atom false)
          exit-code (atom nil)]
      (binding [sut/*tui-available?* true
                sut/*start-standalone-tui!* (fn [opts]
                                              (reset! started opts))]
        (with-redefs [messages/t (fn
                                   ([k] (name k))
                                   ([k params] (str (name k) params)))
                      display/print-info (fn [& _] nil)
                      clojure.core/shutdown-agents (fn [] (reset! shut-down? true))
                      sut/exit! (fn [code] (reset! exit-code code))]
          (sut/tui-cmd {:events-dir "/tmp/events"}))
        (is (= {:events-dir "/tmp/events"} @started))
        (is @shut-down?)
        (is (= 0 @exit-code))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} web-cmd-end-of-input-does-not-open-browser-test
  (testing "a terminal whose stdin ends (Ctrl-D) without Enter: no browser"
    (is (= [] (:opened (web-cmd-with-stdin "" true))))))

(deftest ^{:stratum 1} web-cmd-enter-opens-browser-test
  (testing "Enter on a terminal opens the dashboard"
    (is (= [["open" "http://localhost:9191"]] (:opened (web-cmd-with-stdin "\n" true))))))

(deftest ^{:stratum 1} web-cmd-without-terminal-does-not-prompt-test
  (testing "stdin not a terminal (a script, an agent): no prompt, no browser, even on Enter"
    (let [{:keys [opened out]} (web-cmd-with-stdin "\n" false)]
      (is (= [] opened))
      (is (not (str/includes? out (messages/t :web/press-enter))))
      (is (str/includes? out (messages/t :web/press-ctrl-c))))))

(deftest ^{:stratum 1} web-cmd-unavailable-uses-message-catalog-test
  (testing "Web dashboard is unavailable when the composed launcher is missing"
    (binding [sut/*web-available?* false
              sut/*start-web-dashboard!* nil]
      (with-redefs [config/load-config (constantly {})
                    messages/t (fn
                                 ([k]
                                  (case k
                                    :web/not-available "WEB-NOT-AVAILABLE"
                                    :web/requires-jvm "WEB-REQUIRES-JVM"
                                    (str "UNEXPECTED:" k)))
                                 ([k params] (str "UNEXPECTED:" k params)))
                    display/print-error println
                    sut/exit! (fn [code] (throw (exit-ex code)))]
        (let [output (with-out-str
                       (try
                         (sut/web-cmd {})
                         (catch clojure.lang.ExceptionInfo e
                           (is (= 1 (:code (ex-data e)))))))]
          (is (.contains output "WEB-NOT-AVAILABLE"))
          (is (.contains output "WEB-REQUIRES-JVM")))))))

(deftest ^{:stratum 1} tui-cmd-unavailable-uses-message-catalog-test
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

(deftest ^{:stratum 1} tui-cmd-missing-launcher-uses-unavailable-path-test
  (testing "TUI is unavailable when the composed launcher is missing"
    (binding [sut/*tui-available?* true
              sut/*start-standalone-tui!* nil]
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
          (is (.contains output "TUI-REQUIRES-JVM")))))))
