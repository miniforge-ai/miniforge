(ns ai.miniforge.cli.messages-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.messages :as messages]))

(deftest message-catalog-renders-placeholders-test
  (let [english-catalog (messages/catalog "en")
        usage-template (:help/usage english-catalog)
        help-template (:main/run-help english-catalog)]
    (testing "messages are loaded from resources and interpolate placeholders"
      (is (= (str/replace usage-template "{binary}" "miniforge")
             (messages/t :help/usage {:binary "miniforge"})))
      (is (= (str/replace help-template "{command}" "miniforge help")
             (messages/t :main/run-help {:command "miniforge help"}))))))

(deftest message-catalog-falls-back-to-english-test
  (testing "unknown locales fall back to the English catalog"
    (let [english-usage (messages/t :help/usage {:binary "moteur"})]
      (with-redefs [messages/active-locale (constantly :fr)]
        (is (= english-usage
               (messages/t :help/usage {:binary "moteur"})))))))
