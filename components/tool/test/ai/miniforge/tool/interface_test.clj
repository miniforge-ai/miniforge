(ns ai.miniforge.tool.interface-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.tool.interface :as tool]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn echo-handler [params _ctx]
  (:message params))

(defn add-handler [params _ctx]
  (+ (:a params) (:b params)))

(defn throwing-handler [_params _ctx]
  (throw (Exception. "Intentional error")))

;------------------------------------------------------------------------------ Layer 1
;; Tool creation tests

(deftest create-tool-test
  (testing "creates tool with all options"
    (let [tool (tool/create-tool
                {:id :test/echo
                 :name "Echo Tool"
                 :description "Echoes the message"
                 :parameters {:message {:type :string :required true}}
                 :handler echo-handler
                 :metadata {:version "1.0"}})]
      (is (some? tool))
      (is (= :test/echo (tool/tool-id tool)))))

  (testing "creates tool with minimal options"
    (let [tool (tool/create-tool
                {:id :test/simple
                 :handler (constantly :ok)})]
      (is (some? tool))
      (is (= :test/simple (tool/tool-id tool)))))

  (testing "throws on non-namespaced id"
    (is (thrown? clojure.lang.ExceptionInfo
                 (tool/create-tool {:id :no-namespace
                                    :handler (constantly :ok)})))))

(deftest tool-info-test
  (testing "returns tool information"
    (let [tool (tool/create-tool
                {:id :test/info
                 :name "Info Tool"
                 :description "Test tool info"
                 :parameters {:x {:required true}}
                 :handler (constantly nil)
                 :metadata {:author "test"}})
          info (tool/tool-info tool)]
      (is (= :test/info (:id info)))
      (is (= "Info Tool" (:name info)))
      (is (= "Test tool info" (:description info)))
      (is (= {:x {:required true}} (:parameters info)))
      (is (= {:author "test"} (:metadata info))))))

;; Registry tests

(deftest create-registry-test
  (testing "creates empty registry"
    (let [registry (tool/create-registry)]
      (is (some? registry))
      (is (empty? (tool/list-tools registry)))))

  (testing "creates registry with logger"
    (let [registry (tool/create-registry {:logger nil})]
      (is (some? registry)))))

(deftest register-tool-test
  (testing "registers a tool"
    (let [registry (tool/create-registry)
          my-tool (tool/create-tool {:id :test/registered
                                     :handler (constantly :ok)})
          id (tool/register! registry my-tool)]
      (is (= :test/registered id))
      (is (= 1 (count (tool/list-tools registry))))))

  (testing "retrieves registered tool"
    (let [registry (tool/create-registry)
          my-tool (tool/create-tool {:id :test/retrieve
                                     :handler (constantly :ok)})
          _ (tool/register! registry my-tool)
          found (tool/get-tool registry :test/retrieve)]
      (is (some? found))
      (is (= :test/retrieve (tool/tool-id found))))))

(deftest unregister-tool-test
  (testing "unregisters a tool"
    (let [registry (tool/create-registry)
          my-tool (tool/create-tool {:id :test/remove
                                     :handler (constantly :ok)})
          _ (tool/register! registry my-tool)
          _ (tool/unregister! registry :test/remove)]
      (is (nil? (tool/get-tool registry :test/remove)))
      (is (empty? (tool/list-tools registry))))))

(deftest find-tools-test
  (testing "finds tools by name"
    (let [registry (tool/create-registry)
          _ (tool/register! registry
                            (tool/create-tool {:id :test/greeting
                                               :name "Greeting Tool"
                                               :handler (constantly nil)}))
          _ (tool/register! registry
                            (tool/create-tool {:id :test/farewell
                                               :name "Farewell Tool"
                                               :handler (constantly nil)}))
          found (tool/find-tools registry "greeting")]
      (is (= 1 (count found)))))

  (testing "finds tools by description"
    (let [registry (tool/create-registry)
          _ (tool/register! registry
                            (tool/create-tool {:id :test/desc
                                               :description "Unique description here"
                                               :handler (constantly nil)}))
          found (tool/find-tools registry "unique")]
      (is (= 1 (count found)))))

  (testing "returns empty for no matches"
    (let [registry (tool/create-registry)]
      (is (empty? (tool/find-tools registry "nonexistent"))))))

;; Execution tests

(deftest execute-test
  (testing "executes tool successfully"
    (let [my-tool (tool/create-tool
                   {:id :test/exec
                    :parameters {:message {:type :string :required true}}
                    :handler echo-handler})
          result (tool/execute my-tool {:message "Hello"} {})]
      (is (tool/success? result))
      (is (= "Hello" (tool/get-result result)))))

  (testing "handles computation in handler"
    (let [calc-tool (tool/create-tool
                     {:id :test/calc
                      :parameters {:a {:required true}
                                   :b {:required true}}
                      :handler add-handler})
          result (tool/execute calc-tool {:a 2 :b 3} {})]
      (is (tool/success? result))
      (is (= 5 (tool/get-result result)))))

  (testing "returns validation error for missing params"
    (let [my-tool (tool/create-tool
                   {:id :test/validate
                    :parameters {:required-param {:required true}}
                    :handler (constantly nil)})
          result (tool/execute my-tool {} {})]
      (is (not (tool/success? result)))
      (is (= "validation_error" (get-in (tool/get-error result) [:type])))))

  (testing "returns execution error on exception"
    (let [bad-tool (tool/create-tool
                    {:id :test/error
                     :handler throwing-handler})
          result (tool/execute bad-tool {} {})]
      (is (not (tool/success? result)))
      (is (= "execution_error" (get-in (tool/get-error result) [:type]))))))

(deftest execute-by-id-test
  (testing "executes tool by id from registry"
    (let [registry (tool/create-registry)
          _ (tool/register! registry
                            (tool/create-tool
                             {:id :test/by-id
                              :handler (fn [params _ctx] (* (:n params) 2))}))
          result (tool/execute-by-id registry :test/by-id {:n 21} {})]
      (is (tool/success? result))
      (is (= 42 (tool/get-result result)))))

  (testing "returns error for unknown tool"
    (let [registry (tool/create-registry)
          result (tool/execute-by-id registry :test/unknown {} {})]
      (is (not (tool/success? result)))
      (is (= "not_found" (get-in (tool/get-error result) [:type]))))))

;; Invocation tracking tests

(deftest invocation-tracking-test
  (testing "records successful invocation"
    (let [context (tool/attach-invocation-tracking {})
          my-tool (tool/create-tool
                   {:id :test/track
                    :parameters {:message {:type :string :required true}}
                    :handler echo-handler})
          result (tool/execute my-tool {:message "Hello"} context)
          invocations (tool/tool-invocations context)
          invocation (first invocations)]
      (is (tool/success? result))
      (is (= 1 (count invocations)))
      (is (= :test/track (:tool/id invocation)))
      (is (= {:message "Hello"} (:tool/args invocation)))
      (is (inst? (:tool/invoked-at invocation)))
      (is (>= (:tool/duration-ms invocation) 0))
      (is (= "Hello" (:tool/result invocation)))))

  (testing "records validation errors"
    (let [context (tool/attach-invocation-tracking {})
          my-tool (tool/create-tool
                   {:id :test/track-error
                    :parameters {:required-param {:required true}}
                    :handler (constantly nil)})
          _result (tool/execute my-tool {} context)
          [invocation] (tool/tool-invocations context)]
      (is (= :test/track-error (:tool/id invocation)))
      (is (= "validation_error" (get-in invocation [:tool/error :type]))))))

;; Protocol method tests (N1 conformance)

(deftest validate-args-test
  (testing "validate-args protocol method works"
    (let [my-tool (tool/create-tool
                   {:id :test/validate
                    :parameters {:x {:required true}
                                 :y {:required false}}
                    :handler (constantly nil)})
          valid-result (tool/validate-args my-tool {:x 10 :y 20})
          invalid-result (tool/validate-args my-tool {:y 20})]
      (is (:valid? valid-result))
      (is (not (:valid? invalid-result)))
      (is (seq (:errors invalid-result)))))

  (testing "validate-args with all required params"
    (let [my-tool (tool/create-tool
                   {:id :test/all-required
                    :parameters {:a {:required true}
                                 :b {:required true}}
                    :handler (constantly nil)})
          result (tool/validate-args my-tool {:a 1 :b 2})]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest get-schema-test
  (testing "get-schema protocol method returns parameters"
    (let [params {:message {:type :string :required true}
                  :count {:type :number :required false}}
          my-tool (tool/create-tool
                   {:id :test/schema
                    :parameters params
                    :handler (constantly nil)})
          schema (tool/get-schema my-tool)]
      (is (= params schema))))

  (testing "get-schema returns empty map for parameterless tool"
    (let [my-tool (tool/create-tool
                   {:id :test/no-params
                    :handler (constantly :ok)})
          schema (tool/get-schema my-tool)]
      (is (map? schema)))))

;; Response helper tests

(deftest response-helpers-test
  (testing "success? returns true for successful result"
    (is (tool/success? {:success true :result "ok"})))

  (testing "success? returns false for failed result"
    (is (not (tool/success? {:success false :error {:type "error"}}))))

  (testing "get-result extracts result"
    (is (= "value" (tool/get-result {:success true :result "value"}))))

  (testing "get-error extracts error"
    (is (= {:type "err"} (tool/get-error {:success false :error {:type "err"}})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.tool.interface-test)

  :leave-this-here)
