(ns ai.miniforge.agent.parser-validation-test
  "Tests for agent response parser validation.
   
   Tests that parsers correctly validate parsed EDN is a map
   and reject other types (Symbol, List, etc.)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.agent.planner :as planner]
            [ai.miniforge.agent.implementer :as implementer]))

(deftest parse-plan-response-validation-test
  (testing "parse-plan-response validates result is a map"
    
    (testing "returns nil for Symbol"
      (let [response "Create"]  ; Parses as Symbol
        (is (nil? (planner/parse-plan-response response)))))
    
    (testing "returns nil for List"
      (let [response "(+ 1 2)"]  ; Parses as List
        (is (nil? (planner/parse-plan-response response)))))
    
    (testing "returns nil for Keyword"
      (let [response ":my-keyword"]  ; Parses as Keyword
        (is (nil? (planner/parse-plan-response response)))))
    
    (testing "returns nil for String literal"
      (let [response "\"hello\""]  ; Parses as String
        (is (nil? (planner/parse-plan-response response)))))
    
    (testing "returns nil for Number"
      (let [response "42"]  ; Parses as Number
        (is (nil? (planner/parse-plan-response response)))))
    
    (testing "returns map when valid EDN map"
      (let [response "{:plan/id #uuid \"123e4567-e89b-12d3-a456-426614174000\" :plan/name \"test\"}"]
        (is (map? (planner/parse-plan-response response)))
        (is (= "test" (:plan/name (planner/parse-plan-response response))))))
    
    (testing "returns map from code block"
      (let [response "```clojure\n{:plan/id #uuid \"123e4567-e89b-12d3-a456-426614174000\" :plan/name \"test\"}\n```"]
        (is (map? (planner/parse-plan-response response)))
        (is (= "test" (:plan/name (planner/parse-plan-response response))))))
    
    (testing "returns nil for unparseable content"
      (let [response "{:invalid edn"]  ; Missing closing brace and value for :invalid
        (is (nil? (planner/parse-plan-response response)))))))

(deftest parse-code-response-validation-test
  (testing "parse-code-response validates result is a map"
    
    (testing "returns nil for Symbol"
      (let [response "Implement"]  ; Parses as Symbol
        (is (nil? (implementer/parse-code-response response)))))
    
    (testing "returns nil for List"
      (let [response "(defn foo [])"]  ; Parses as List
        (is (nil? (implementer/parse-code-response response)))))
    
    (testing "returns map when valid EDN map"
      (let [response "{:code/id #uuid \"123e4567-e89b-12d3-a456-426614174000\" :code/files []}"]
        (is (map? (implementer/parse-code-response response)))
        (is (vector? (:code/files (implementer/parse-code-response response))))))
    
    (testing "returns map from code block"
      (let [response "```edn\n{:code/id #uuid \"123e4567-e89b-12d3-a456-426614174000\" :code/files []}\n```"]
        (is (map? (implementer/parse-code-response response)))))

    (testing "extracts inline {:status :already-implemented} from reasoning text"
      (let [response "Looking at the existing files, this feature is already fully implemented.\n\n{:status :already-implemented :summary \"The login module already exists with email verification.\"}"]
        (is (= :already-implemented (:status (implementer/parse-code-response response))))
        (is (string? (:summary (implementer/parse-code-response response))))))

    (testing "returns nil for reasoning text without EDN map"
      (let [response "I've analyzed the codebase and the feature looks good. No changes needed."]
        (is (nil? (implementer/parse-code-response response)))))))
