(ns ai.miniforge.cli.main.display-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.main.display :as sut]))

(deftest print-error-uses-message-catalog-test
  (testing "generic error output reads its prefix from the message catalog"
    (with-redefs [messages/t (fn [k params]
                               (case k
                                 :classified-error/error-prefix (str "CAT:" (:message params))
                                 (str "UNEXPECTED:" k)))]
      (let [output (with-out-str (sut/print-error "boom"))]
        (is (.contains output "CAT:boom"))))))
