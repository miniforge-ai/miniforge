(ns ai.miniforge.tool-registry.interface-test
  "Tests for tool-registry public interface."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.tool-registry.interface :as tool-registry]))

;------------------------------------------------------------------------------ Test fixtures

(def ^:dynamic *registry* nil)

(defn registry-fixture [f]
  (binding [*registry* (tool-registry/create-registry {})]
    (f)))

(use-fixtures :each registry-fixture)

;------------------------------------------------------------------------------ Sample data

(def sample-tool
  {:tool/id :test/sample
   :tool/type :function
   :tool/name "Sample Tool"
   :tool/description "A sample function tool"
   :tool/capabilities #{:code/completion}
   :tool/tags #{:test}})

;------------------------------------------------------------------------------ Interface tests

(deftest create-registry-test
  (testing "creates registry with default options"
    (let [reg (tool-registry/create-registry)]
      (is (some? reg))
      (is (empty? (tool-registry/list-tools reg)))))

  (testing "creates registry with options"
    (let [reg (tool-registry/create-registry {:project-dir "/tmp"})]
      (is (some? reg)))))

(deftest register-and-get-test
  (testing "register! and get-tool work together"
    (tool-registry/register! *registry* sample-tool)
    (let [tool (tool-registry/get-tool *registry* :test/sample)]
      (is (some? tool))
      (is (= "Sample Tool" (:tool/name tool))))))

(deftest list-and-find-test
  (testing "list-tools returns all tools"
    (tool-registry/register! *registry* sample-tool)
    (tool-registry/register! *registry* (assoc sample-tool
                                               :tool/id :test/another
                                               :tool/name "Another Tool"))
    (is (= 2 (count (tool-registry/list-tools *registry*)))))

  (testing "find-tools with query"
    ;; Note: sample-tool and :test/another from previous test are still registered
    ;; Register an LSP tool
    (tool-registry/register! *registry* (assoc sample-tool
                                               :tool/id :lsp/test
                                               :tool/type :lsp
                                               :tool/name "LSP Tool"
                                               :tool/config {:lsp/command ["test"]}))
    (let [function-tools (tool-registry/find-tools *registry* {:type :function})
          lsp-tools (tool-registry/find-tools *registry* {:type :lsp})]
      ;; 2 function tools from list-tools test + sample-tool
      (is (= 2 (count function-tools)))
      (is (= 1 (count lsp-tools))))))

(deftest update-and-enable-test
  (testing "update-tool! modifies tool"
    (tool-registry/register! *registry* sample-tool)
    (tool-registry/update-tool! *registry* :test/sample
                                {:tool/description "Updated description"})
    (let [tool (tool-registry/get-tool *registry* :test/sample)]
      (is (= "Updated description" (:tool/description tool)))))

  (testing "enable-tool! sets enabled to true"
    (tool-registry/register! *registry* (assoc sample-tool :tool/enabled false))
    (tool-registry/enable-tool! *registry* :test/sample)
    (let [tool (tool-registry/get-tool *registry* :test/sample)]
      (is (:tool/enabled tool))))

  (testing "disable-tool! sets enabled to false"
    (tool-registry/register! *registry* sample-tool)
    (tool-registry/disable-tool! *registry* :test/sample)
    (let [tool (tool-registry/get-tool *registry* :test/sample)]
      (is (not (:tool/enabled tool))))))

(deftest unregister-test
  (testing "unregister! removes tool"
    (tool-registry/register! *registry* sample-tool)
    (tool-registry/unregister! *registry* :test/sample)
    (is (nil? (tool-registry/get-tool *registry* :test/sample)))))

(deftest tools-for-context-test
  (testing "returns enabled tools with required capabilities"
    (tool-registry/register! *registry* sample-tool)
    (tool-registry/register! *registry* (assoc sample-tool
                                               :tool/id :test/disabled
                                               :tool/enabled false))
    (let [tools (tool-registry/tools-for-context *registry* #{:code/completion})]
      (is (= 1 (count tools)))
      (is (= :test/sample (:tool/id (first tools)))))))

(deftest registry-stats-test
  (testing "returns correct statistics"
    (tool-registry/register! *registry* sample-tool)
    (tool-registry/register! *registry* (assoc sample-tool
                                               :tool/id :lsp/test
                                               :tool/type :lsp
                                               :tool/config {:lsp/command ["test"]}))
    (let [stats (tool-registry/registry-stats *registry*)]
      (is (= 2 (:total stats)))
      (is (= 2 (:enabled stats)))
      (is (= {:function 1 :lsp 1} (:by-type stats))))))

;------------------------------------------------------------------------------ Schema re-export tests

(deftest schema-re-exports-test
  (testing "tool-types is available"
    (is (contains? tool-registry/tool-types :lsp))
    (is (contains? tool-registry/tool-types :function)))

  (testing "lsp-capabilities is available"
    (is (contains? tool-registry/lsp-capabilities :diagnostics))
    (is (contains? tool-registry/lsp-capabilities :format))))
