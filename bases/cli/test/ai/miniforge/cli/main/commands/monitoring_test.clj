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
