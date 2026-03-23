(ns ai.miniforge.messages.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.messages.interface :as messages]))

(deftest create-translator-test
  (testing "create-translator returns a callable function"
    ;; Use a resource path that won't exist — should fall back to key name
    (let [t (messages/create-translator "nonexistent.edn" :test/messages)]
      (is (fn? t))
      (is (= "some-key" (t :some-key))))))

(deftest t-with-catalog-test
  (testing "t substitutes params into template"
    (let [catalog (delay {:greeting "Hello, {name}!"})]
      (is (= "Hello, World!" (messages/t catalog :greeting {:name "World"})))))

  (testing "t falls back to key name for missing keys"
    (let [catalog (delay {})]
      (is (= "missing-key" (messages/t catalog :missing-key))))))
