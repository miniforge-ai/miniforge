(ns ai.miniforge.cli.messages-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.messages :as messages]))

(deftest message-catalog-renders-placeholders-test
  (testing "messages are loaded from EDN resources and rendered with parameters"
    (is (= "Usage: miniforge <command> [options]"
           (messages/t :help/usage {:binary "miniforge"})))
    (is (= "Run 'miniforge help' for more information."
           (messages/t :main/run-help {:command "miniforge help"})))))

(deftest message-catalog-falls-back-to-english-test
  (testing "unknown locales fall back to the English catalog"
    (with-redefs [messages/active-locale (constantly :fr)]
      (is (= "Usage: moteur <command> [options]"
             (messages/t :help/usage {:binary "moteur"}))))))
