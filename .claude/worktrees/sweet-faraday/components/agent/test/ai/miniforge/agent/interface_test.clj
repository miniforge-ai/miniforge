(ns ai.miniforge.agent.interface-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.agent.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def test-task
  {:task/id (random-uuid)
   :task/type :implement
   :task/status :pending
   :task/inputs []})

;------------------------------------------------------------------------------ Layer 1
;; Agent creation tests

(deftest create-agent-test
  (testing "creates agent with default config"
    (let [a (agent/create-agent :implementer)]
      (is (some? a))
      (is (= :implementer (:role a)))
      (is (uuid? (:id a)))
      (is (uuid? (:memory-id a)))))

  (testing "creates agent with custom config"
    (let [a (agent/create-agent :planner {:model "claude-opus-4"
                                          :max-tokens 32000})]
      (is (= :planner (:role a)))
      (is (= "claude-opus-4" (get-in a [:config :model])))
      (is (= 32000 (get-in a [:config :max-tokens])))))

  (testing "creates all role types"
    (doseq [role [:planner :architect :implementer :tester :reviewer
                  :sre :security :release :historian :operator]]
      (let [a (agent/create-agent role)]
        (is (= role (:role a)))
        (is (some? (:config a)))))))

(deftest create-agent-map-test
  (testing "returns schema-conformant map"
    (let [m (agent/create-agent-map :implementer)]
      (is (map? m))
      (is (uuid? (:agent/id m)))
      (is (= :implementer (:agent/role m)))
      (is (set? (:agent/capabilities m)))
      (is (uuid? (:agent/memory m)))
      (is (map? (:agent/config m)))))

  (testing "includes custom config"
    (let [m (agent/create-agent-map :tester {:max-tokens 4000})]
      (is (= 4000 (get-in m [:agent/config :max-tokens]))))))

;------------------------------------------------------------------------------ Layer 2
;; Agent lifecycle tests

(deftest agent-lifecycle-test
  (testing "init updates config"
    (let [a (agent/create-agent :implementer)
          initialized (agent/init a {:max-tokens 2000})]
      (is (= 2000 (get-in initialized [:config :max-tokens])))))

  (testing "status returns agent state"
    (let [a (agent/create-agent :implementer)
          initialized (agent/init a {})
          status (agent/agent-status initialized)]
      (is (map? status))
      (is (contains? status :agent-id))
      (is (contains? status :role))))

  (testing "shutdown updates state"
    (let [a (agent/create-agent :implementer)
          shutdown-agent (agent/shutdown a)]
      (is (= :shutdown (get-in shutdown-agent [:state :status]))))))

;------------------------------------------------------------------------------ Layer 3
;; Agent execution tests

(deftest execute-test
  (testing "executes agent with mock LLM"
    (let [executor (agent/create-executor)
          a (agent/create-agent :implementer)
          mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"
                                           :usage {:input-tokens 200 :output-tokens 100}
                                           :model "mock"})
          result (agent/execute executor a test-task {:llm-backend mock-llm})]
      (is (map? result))
      (is (contains? result :success))
      (is (contains? result :outputs))
      (is (contains? result :metrics))
      (is (uuid? (:agent-id result)))
      (is (uuid? (:task-id result)))))

  (testing "executes without LLM backend"
    (let [executor (agent/create-executor)
          a (agent/create-agent :implementer)
          result (agent/execute executor a test-task {})]
      (is (map? result))
      (is (:success result))
      (is (contains? result :metrics)))))

(deftest invoke-test
  (testing "invoke returns structured output"
    (let [a (agent/create-agent :implementer)
          mock-llm (agent/create-mock-llm {:content "test"
                                           :usage {:input-tokens 10 :output-tokens 5}
                                           :model "mock"})
          result (agent/invoke a test-task {:llm-backend mock-llm})]
      (is (map? result))
      ;; BaseAgent invoke returns {:success bool :outputs [...] ...}
      (is (contains? result :success))
      (is (contains? result :outputs)))))

(deftest validate-test
  (testing "validates correct output"
    (let [a (agent/create-agent :implementer)
          output {:success true :outputs [] :decisions [] :signals []}
          validation (agent/validate a output {})]
      (is (:valid? validation))
      (is (empty? (:errors validation)))))

  (testing "detects invalid output"
    (let [a (agent/create-agent :implementer)
          validation (agent/validate a "not a map" {})]
      (is (not (:valid? validation)))
      (is (seq (:errors validation))))))

(deftest repair-test
  (testing "repair returns wrapped output for errors"
    (let [a (agent/create-agent :implementer)
          result (agent/repair a "invalid" ["Output must be a map"] {})]
      (is (map? result))
      (is (map? (:repaired result)))
      (is (contains? (:repaired result) :errors)))))

;------------------------------------------------------------------------------ Layer 4
;; Memory tests

(deftest create-memory-test
  (testing "creates memory with defaults"
    (let [mem (agent/create-memory)]
      (is (some? mem))
      (is (= [] (agent/get-messages mem)))))

  (testing "creates memory with scope"
    (let [scope-id (random-uuid)
          mem (agent/create-memory {:scope :task :scope-id scope-id})
          meta (agent/memory-metadata mem)]
      (is (= :task (:memory/scope meta)))
      (is (= scope-id (:scope-id meta))))))

(deftest add-message-test
  (testing "adds messages to memory"
    (let [mem (-> (agent/create-memory)
                  (agent/add-system-message "System prompt")
                  (agent/add-user-message "User message")
                  (agent/add-assistant-message "Assistant response"))
          messages (agent/get-messages mem)]
      (is (= 3 (count messages)))
      (is (= :system (:memory/role (first messages))))
      (is (= :user (:memory/role (second messages))))
      (is (= :assistant (:memory/role (nth messages 2))))))

  (testing "add-to-memory works with any role"
    (let [mem (-> (agent/create-memory)
                  (agent/add-to-memory :tool "Tool result"))
          messages (agent/get-messages mem)]
      (is (= 1 (count messages)))
      (is (= :tool (:memory/role (first messages)))))))

(deftest get-memory-window-test
  (testing "returns all messages within limit"
    (let [mem (-> (agent/create-memory)
                  (agent/add-user-message "Hello")
                  (agent/add-assistant-message "Hi"))
          window (agent/get-memory-window mem 1000)]
      (is (= 2 (count (:messages window))))
      (is (= 0 (:trimmed-count window)))))

  (testing "trims messages exceeding limit"
    (let [mem (-> (agent/create-memory)
                  (agent/add-user-message (apply str (repeat 500 "a")))
                  (agent/add-assistant-message (apply str (repeat 500 "b"))))
          window (agent/get-memory-window mem 100)]
      ;; Should trim at least one message
      (is (<= (count (:messages window)) 2))
      (is (<= (:total-tokens window) 100)))))

(deftest clear-memory-test
  (testing "clears all messages"
    (let [mem (-> (agent/create-memory)
                  (agent/add-user-message "Hello")
                  (agent/add-assistant-message "Hi")
                  (agent/clear-memory))]
      (is (= [] (agent/get-messages mem))))))

;------------------------------------------------------------------------------ Layer 5
;; Memory store tests

(deftest memory-store-test
  (testing "saves and retrieves memory"
    (let [store (agent/create-memory-store)
          mem (agent/create-memory {:scope :task :scope-id (random-uuid)})
          mem-with-msg (agent/add-user-message mem "Test")
          _ (agent/save-memory store mem-with-msg)
          retrieved (agent/get-memory store (:id mem-with-msg))]
      (is (some? retrieved))
      (is (= 1 (count (agent/get-messages retrieved))))))

  (testing "deletes memory"
    (let [store (agent/create-memory-store)
          mem (agent/create-memory)
          _ (agent/save-memory store mem)
          _ (agent/delete-memory store (:id mem))
          retrieved (agent/get-memory store (:id mem))]
      (is (nil? retrieved))))

  (testing "lists memories by scope"
    (let [store (agent/create-memory-store)
          scope-id (random-uuid)
          mem1 (agent/create-memory {:scope :task :scope-id scope-id})
          mem2 (agent/create-memory {:scope :task :scope-id scope-id})
          mem3 (agent/create-memory {:scope :task :scope-id (random-uuid)})
          _ (agent/save-memory store mem1)
          _ (agent/save-memory store mem2)
          _ (agent/save-memory store mem3)
          listed (agent/list-memories store :task scope-id)]
      (is (= 2 (count listed))))))

;------------------------------------------------------------------------------ Layer 6
;; Utility tests

(deftest estimate-tokens-test
  (testing "estimates tokens for string"
    (is (pos? (agent/estimate-tokens "Hello, world!")))
    ;; Approximately 4 chars per token
    (is (< (agent/estimate-tokens "test") 10)))

  (testing "handles non-string content"
    (is (= 100 (agent/estimate-tokens {:complex :data})))))

(deftest estimate-cost-test
  (testing "calculates cost for tokens"
    (let [cost (agent/estimate-cost 1000 500 "claude-sonnet-4")]
      (is (pos? cost))
      (is (< cost 1.0)))) ;; Should be cents, not dollars

  (testing "different models have different costs"
    (let [sonnet-cost (agent/estimate-cost 1000 500 "claude-sonnet-4")
          opus-cost (agent/estimate-cost 1000 500 "claude-opus-4")]
      (is (< sonnet-cost opus-cost)))))

(deftest mock-llm-test
  (testing "returns configured response"
    (let [mock (agent/create-mock-llm {:content "Test response"
                                       :usage {:input-tokens 50 :output-tokens 25}
                                       :model "test-model"})
          ;; Access protocol method through core
          result (proto/complete mock [] {})]
      (is (= "Test response" (:content result)))
      (is (= 50 (get-in result [:usage :input-tokens])))))

  (testing "cycles through sequence of responses"
    (let [mock (agent/create-mock-llm [{:content "First" :usage {} :model "m"}
                                       {:content "Second" :usage {} :model "m"}])
          r1 (proto/complete mock [] {})
          r2 (proto/complete mock [] {})]
      (is (= "First" (:content r1)))
      (is (= "Second" (:content r2))))))

;------------------------------------------------------------------------------ Layer 7
;; Configuration tests

(deftest role-configs-test
  (testing "all roles have configurations"
    (doseq [role [:planner :architect :implementer :tester :reviewer
                  :sre :security :release :historian :operator]]
      (let [config (get agent/default-role-configs role)]
        (is (some? config) (str "Missing config for " role))
        (is (contains? config :model))
        (is (contains? config :temperature))
        (is (contains? config :max-tokens))
        (is (contains? config :budget)))))

  (testing "all roles have capabilities"
    (doseq [role [:planner :architect :implementer :tester :reviewer
                  :sre :security :release :historian :operator]]
      (let [caps (get agent/role-capabilities role)]
        (is (set? caps) (str "Missing capabilities for " role))
        (is (pos? (count caps))))))

  (testing "all roles have system prompts"
    (doseq [role [:planner :architect :implementer :tester :reviewer
                  :sre :security :release :historian :operator]]
      (let [prompt (get agent/role-system-prompts role)]
        (is (string? prompt) (str "Missing system prompt for " role))
        (is (pos? (count prompt)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.interface-test)

  ;; Run specific test
  (test/test-var #'create-agent-test)

  :leave-this-here)
