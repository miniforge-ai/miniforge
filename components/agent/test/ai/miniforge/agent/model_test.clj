(ns ai.miniforge.agent.model-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0
;; Default model policy

(deftest default-model-for-role-test
  (testing "thinking roles default to GPT thinking model"
    (with-redefs [config/load-merged-config
                  (fn []
                    {:agents {:default-models {:thinking "gpt-5.4"
                                               :execution "gpt-5.2-codex"}}})]
      (is (= "gpt-5.4" (model/default-model-for-role :planner)))))

  (testing "execution roles default to GPT execution model"
    (with-redefs [config/load-merged-config
                  (fn []
                    {:agents {:default-models {:thinking "gpt-5.4"
                                               :execution "gpt-5.2-codex"}}})]
      (is (= "gpt-5.2-codex" (model/default-model-for-role :implementer)))
      (is (= "gpt-5.2-codex" (model/default-model-for-role :reviewer))))))

;------------------------------------------------------------------------------ Layer 1
;; Backend resolution

(deftest resolve-llm-client-for-role-test
  (testing "reuses the provided client when it already matches the role backend"
    (let [client (llm/create-client {:backend :codex})]
      (with-redefs [config/load-merged-config
                    (fn []
                      {:agents {:default-models {:thinking "gpt-5.4"
                                                 :execution "gpt-5.2-codex"}}})]
        (is (= client (model/resolve-llm-client-for-role :implementer client))))))

  (testing "creates a role-specific client when execution model needs a different backend"
    (let [shared-client (llm/create-client {:backend :claude})
          resolved (with-redefs [config/load-merged-config
                                 (fn []
                                   {:agents {:default-models {:thinking "gpt-5.4"
                                                              :execution "gpt-5.2-codex"}}})]
                     (model/resolve-llm-client-for-role :implementer shared-client))]
      (is (= :codex (llm/client-backend resolved)))))

  (testing "returns nil when no shared client is available"
    (let [resolved (with-redefs [config/load-merged-config
                                 (fn []
                                   {:agents {:default-models {:thinking "gpt-5.4"
                                                              :execution "gpt-5.2-codex"}}})]
                     (model/resolve-llm-client-for-role :implementer nil))]
      (is (nil? resolved)))))
