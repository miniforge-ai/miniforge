(ns ai.miniforge.tool-registry.registry-test
  "Tests for tool-registry registry operations."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.tool-registry.registry :as registry]))

;------------------------------------------------------------------------------ Test fixtures

(def ^:dynamic *registry* nil)

(defn registry-fixture [f]
  (binding [*registry* (registry/create-registry {})]
    (f)))

(use-fixtures :each registry-fixture)

;------------------------------------------------------------------------------ Sample tools

(def sample-lsp-tool
  {:tool/id :lsp/test
   :tool/type :lsp
   :tool/name "Test LSP"
   :tool/description "Test language server"
   :tool/config {:lsp/command ["test-lsp"]}
   :tool/capabilities #{:code/diagnostics :code/format}
   :tool/tags #{:test :language-server}})

(def sample-function-tool
  {:tool/id :tools/greet
   :tool/type :function
   :tool/name "Greet"
   :tool/description "Greets users by name"
   :tool/capabilities #{:code/completion}
   :tool/tags #{:greeting}})

;------------------------------------------------------------------------------ Registration tests

(deftest register-tool-test
  (testing "register valid tool returns tool ID"
    (let [tool-id (registry/register-tool *registry* sample-lsp-tool)]
      (is (= :lsp/test tool-id))))

  (testing "registered tool can be retrieved"
    (registry/register-tool *registry* sample-lsp-tool)
    (let [tool (registry/get-tool *registry* :lsp/test)]
      (is (some? tool))
      (is (= "Test LSP" (:tool/name tool)))))

  (testing "register invalid tool throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid tool configuration"
         (registry/register-tool *registry* {:tool/id :bad}))))

  (testing "register tool with non-namespaced ID throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Tool ID must be a namespaced keyword"
         (registry/register-tool *registry*
                                 {:tool/id :not-namespaced
                                  :tool/type :function
                                  :tool/name "Bad ID"})))))

;------------------------------------------------------------------------------ List and find tests

(deftest list-tools-test
  (testing "empty registry returns empty list"
    (is (empty? (registry/list-tools *registry*))))

  (testing "lists all registered tools"
    (registry/register-tool *registry* sample-lsp-tool)
    (registry/register-tool *registry* sample-function-tool)
    (let [tools (registry/list-tools *registry*)]
      (is (= 2 (count tools))))))

(deftest find-tools-test
  (testing "find by type"
    (registry/register-tool *registry* sample-lsp-tool)
    (registry/register-tool *registry* sample-function-tool)
    (let [lsp-tools (registry/find-tools *registry* {:type :lsp})
          function-tools (registry/find-tools *registry* {:type :function})]
      (is (= 1 (count lsp-tools)))
      (is (= :lsp/test (:tool/id (first lsp-tools))))
      (is (= 1 (count function-tools)))
      (is (= :tools/greet (:tool/id (first function-tools))))))

  (testing "find by capabilities"
    (registry/register-tool *registry* sample-lsp-tool)
    (registry/register-tool *registry* sample-function-tool)
    (let [diagnostic-tools (registry/find-tools *registry*
                                                {:capabilities #{:code/diagnostics}})]
      (is (= 1 (count diagnostic-tools)))
      (is (= :lsp/test (:tool/id (first diagnostic-tools))))))

  (testing "find by tags"
    (registry/register-tool *registry* sample-lsp-tool)
    (registry/register-tool *registry* sample-function-tool)
    (let [test-tools (registry/find-tools *registry* {:tags #{:test}})]
      (is (= 1 (count test-tools)))
      (is (= :lsp/test (:tool/id (first test-tools))))))

  (testing "find by text"
    (registry/register-tool *registry* sample-lsp-tool)
    (registry/register-tool *registry* sample-function-tool)
    (let [greet-tools (registry/find-tools *registry* {:text "greet"})]
      (is (= 1 (count greet-tools)))
      (is (= :tools/greet (:tool/id (first greet-tools))))))

  (testing "find by enabled status"
    (registry/register-tool *registry* sample-lsp-tool)
    (registry/register-tool *registry* (assoc sample-function-tool :tool/enabled false))
    (let [enabled-tools (registry/find-tools *registry* {:enabled true})]
      (is (= 1 (count enabled-tools)))
      (is (= :lsp/test (:tool/id (first enabled-tools)))))))

;------------------------------------------------------------------------------ Update tests

(deftest update-tool-test
  (testing "update tool returns updated config"
    (registry/register-tool *registry* sample-lsp-tool)
    (let [updated (registry/update-tool *registry* :lsp/test
                                        {:tool/description "Updated description"})]
      (is (= "Updated description" (:tool/description updated)))))

  (testing "update preserves other fields"
    (registry/register-tool *registry* sample-lsp-tool)
    (registry/update-tool *registry* :lsp/test {:tool/description "Updated"})
    (let [tool (registry/get-tool *registry* :lsp/test)]
      (is (= "Test LSP" (:tool/name tool)))
      (is (= :lsp (:tool/type tool)))))

  (testing "update non-existent tool throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Tool not found"
         (registry/update-tool *registry* :nonexistent {:tool/name "New"})))))

;------------------------------------------------------------------------------ Unregister tests

(deftest unregister-tool-test
  (testing "unregister removes tool"
    (registry/register-tool *registry* sample-lsp-tool)
    (registry/unregister-tool *registry* :lsp/test)
    (is (nil? (registry/get-tool *registry* :lsp/test))))

  (testing "unregister returns tool ID"
    (registry/register-tool *registry* sample-lsp-tool)
    (let [tool-id (registry/unregister-tool *registry* :lsp/test)]
      (is (= :lsp/test tool-id)))))

;------------------------------------------------------------------------------ Helper function tests

(deftest tools-for-context-test
  (testing "returns enabled tools with matching capabilities"
    (registry/register-tool *registry* sample-lsp-tool)
    (registry/register-tool *registry* sample-function-tool)
    (registry/register-tool *registry* (assoc sample-lsp-tool
                                              :tool/id :lsp/disabled
                                              :tool/enabled false))
    (let [tools (registry/tools-for-context *registry* #{:code/diagnostics})]
      (is (= 1 (count tools)))
      (is (= :lsp/test (:tool/id (first tools)))))))

(deftest registry-stats-test
  (testing "returns correct statistics"
    (registry/register-tool *registry* sample-lsp-tool)
    (registry/register-tool *registry* sample-function-tool)
    (registry/register-tool *registry* (assoc sample-function-tool
                                              :tool/id :tools/disabled
                                              :tool/enabled false))
    (let [stats (registry/registry-stats *registry*)]
      (is (= 3 (:total stats)))
      (is (= 2 (:enabled stats)))
      (is (= {:lsp 1 :function 2} (:by-type stats))))))
