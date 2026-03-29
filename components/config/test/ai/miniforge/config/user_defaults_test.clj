(ns ai.miniforge.config.user-defaults-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.config.user :as user]))

;------------------------------------------------------------------------------ Layer 0
;; Default config policy

(deftest default-config-prefers-gpt-execution-test
  (let [cfg config/default-config]
    (testing "default llm backend prefers codex"
      (is (= :codex (get-in cfg [:llm :backend])))
      (is (= "gpt-5.2-codex" (get-in cfg [:llm :model]))))

    (testing "default agent models use GPT for thinking and execution"
      (is (= "gpt-5.4" (get-in cfg [:agents :default-models :thinking])))
      (is (= "gpt-5.2-codex" (get-in cfg [:agents :default-models :execution]))))

    (testing "default self-healing enables codex failover"
      (is (= [:codex] (get-in cfg [:self-healing :allowed-failover-backends]))))))

(deftest load-default-config-falls-back-to-edn-resource-test
  (let [orig-resource io/resource]
    (with-redefs [io/resource (fn [path]
                                (case path
                                  "config/default-user-config.edn" nil
                                  "config/default-user-config-fallback.edn"
                                  (orig-resource "config/default-user-config-fallback.edn")
                                  nil))]
      (let [cfg (user/load-default-config)]
        (is (= :codex (get-in cfg [:llm :backend])))
        (is (= "gpt-5.2-codex" (get-in cfg [:agents :default-models :execution])))))))
