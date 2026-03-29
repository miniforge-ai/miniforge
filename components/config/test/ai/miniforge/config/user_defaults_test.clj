(ns ai.miniforge.config.user-defaults-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.config.interface :as config]))

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
