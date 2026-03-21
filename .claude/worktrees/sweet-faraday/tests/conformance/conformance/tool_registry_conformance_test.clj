(ns conformance.tool-registry-conformance-test
  "Conformance tests for the tool-registry component.

   Verifies:
   - Tool registry component loads and provides required interface
   - LSP support is properly structured
   - Tool discovery and management works correctly
   - Integration with agent context for tool selection"
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Component Loading

(deftest tool-registry-component-loads-test
  (testing "tool-registry component loads successfully"
    (require 'ai.miniforge.tool-registry.interface)
    (let [tr-ns (find-ns 'ai.miniforge.tool-registry.interface)]
      (is (some? tr-ns) "tool-registry interface namespace must exist"))))

(deftest tool-registry-interface-functions-test
  (testing "tool-registry interface provides required functions"
    (require 'ai.miniforge.tool-registry.interface)
    (let [tr-ns (find-ns 'ai.miniforge.tool-registry.interface)]
      ;; Registry management
      (is (some? (ns-resolve tr-ns 'create-registry)) "create-registry function exists")
      (is (some? (ns-resolve tr-ns 'register!)) "register! function exists")
      (is (some? (ns-resolve tr-ns 'unregister!)) "unregister! function exists")
      (is (some? (ns-resolve tr-ns 'get-tool)) "get-tool function exists")
      (is (some? (ns-resolve tr-ns 'list-tools)) "list-tools function exists")
      (is (some? (ns-resolve tr-ns 'find-tools)) "find-tools function exists")

      ;; LSP lifecycle
      (is (some? (ns-resolve tr-ns 'start-lsp)) "start-lsp function exists")
      (is (some? (ns-resolve tr-ns 'stop-lsp)) "stop-lsp function exists")
      (is (some? (ns-resolve tr-ns 'lsp-status)) "lsp-status function exists")

      ;; Tool loading
      (is (some? (ns-resolve tr-ns 'load-tools)) "load-tools function exists")
      (is (some? (ns-resolve tr-ns 'discover-tools)) "discover-tools function exists")

      ;; Agent integration
      (is (some? (ns-resolve tr-ns 'tools-for-context)) "tools-for-context function exists")
      (is (some? (ns-resolve tr-ns 'tools-with-status)) "tools-with-status function exists"))))

(deftest tool-registry-schema-exports-test
  (testing "tool-registry exports Malli schemas"
    (require 'ai.miniforge.tool-registry.interface)
    (let [tr-ns (find-ns 'ai.miniforge.tool-registry.interface)]
      (is (some? (ns-resolve tr-ns 'ToolConfig)) "ToolConfig schema is exported")
      (is (some? (ns-resolve tr-ns 'LSPConfig)) "LSPConfig schema is exported")
      (is (some? (ns-resolve tr-ns 'tool-types)) "tool-types set is exported")
      (is (some? (ns-resolve tr-ns 'lsp-capabilities)) "lsp-capabilities set is exported"))))

;------------------------------------------------------------------------------ Layer 1
;; LSP Support Structure

(deftest lsp-protocol-namespace-test
  (testing "LSP protocol namespace loads"
    (require 'ai.miniforge.tool-registry.lsp.protocol)
    (let [proto-ns (find-ns 'ai.miniforge.tool-registry.lsp.protocol)]
      (is (some? proto-ns) "lsp.protocol namespace exists")
      ;; Check key protocol functions
      (is (some? (ns-resolve proto-ns 'request)) "request builder exists")
      (is (some? (ns-resolve proto-ns 'notification)) "notification builder exists")
      (is (some? (ns-resolve proto-ns 'encode-message)) "encode-message exists")
      (is (some? (ns-resolve proto-ns 'decode-message)) "decode-message exists"))))

(deftest lsp-client-namespace-test
  (testing "LSP client namespace loads"
    (require 'ai.miniforge.tool-registry.lsp.client)
    (let [client-ns (find-ns 'ai.miniforge.tool-registry.lsp.client)]
      (is (some? client-ns) "lsp.client namespace exists")
      ;; Check key client functions
      (is (some? (ns-resolve client-ns 'create-client)) "create-client exists")
      (is (some? (ns-resolve client-ns 'initialize)) "initialize exists")
      (is (some? (ns-resolve client-ns 'shutdown)) "shutdown exists")
      (is (some? (ns-resolve client-ns 'hover)) "hover exists")
      (is (some? (ns-resolve client-ns 'completion)) "completion exists")
      (is (some? (ns-resolve client-ns 'format-document)) "format-document exists"))))

(deftest lsp-manager-namespace-test
  (testing "LSP manager namespace loads"
    (require 'ai.miniforge.tool-registry.lsp.manager)
    (let [manager-ns (find-ns 'ai.miniforge.tool-registry.lsp.manager)]
      (is (some? manager-ns) "lsp.manager namespace exists")
      ;; Check key manager functions
      (is (some? (ns-resolve manager-ns 'start-server)) "start-server exists")
      (is (some? (ns-resolve manager-ns 'stop-server)) "stop-server exists")
      (is (some? (ns-resolve manager-ns 'get-client)) "get-client exists")
      (is (some? (ns-resolve manager-ns 'server-status)) "server-status exists")
      (is (some? (ns-resolve manager-ns 'list-servers)) "list-servers exists"))))

;------------------------------------------------------------------------------ Layer 2
;; Functional Tests

(deftest registry-crud-operations-test
  (testing "Registry CRUD operations work correctly"
    (require '[ai.miniforge.tool-registry.interface :as tr])
    (let [create-registry (ns-resolve 'ai.miniforge.tool-registry.interface 'create-registry)
          register! (ns-resolve 'ai.miniforge.tool-registry.interface 'register!)
          get-tool (ns-resolve 'ai.miniforge.tool-registry.interface 'get-tool)
          list-tools (ns-resolve 'ai.miniforge.tool-registry.interface 'list-tools)
          unregister! (ns-resolve 'ai.miniforge.tool-registry.interface 'unregister!)

          registry (create-registry {})
          tool {:tool/id :test/conformance-tool
                :tool/type :function
                :tool/name "Conformance Test Tool"
                :tool/description "A tool for conformance testing"
                :tool/capabilities #{:code/diagnostics}}]

      ;; Register
      (let [tool-id (register! registry tool)]
        (is (= :test/conformance-tool tool-id) "register! returns tool ID"))

      ;; Get
      (let [retrieved (get-tool registry :test/conformance-tool)]
        (is (some? retrieved) "get-tool retrieves registered tool")
        (is (= "Conformance Test Tool" (:tool/name retrieved))))

      ;; List
      (let [tools (list-tools registry)]
        (is (= 1 (count tools)) "list-tools returns all tools"))

      ;; Unregister
      (unregister! registry :test/conformance-tool)
      (is (nil? (get-tool registry :test/conformance-tool)) "unregister! removes tool"))))

(deftest tool-discovery-by-capability-test
  (testing "Tools can be discovered by capability"
    (require '[ai.miniforge.tool-registry.interface :as tr])
    (let [create-registry (ns-resolve 'ai.miniforge.tool-registry.interface 'create-registry)
          register! (ns-resolve 'ai.miniforge.tool-registry.interface 'register!)
          tools-for-context (ns-resolve 'ai.miniforge.tool-registry.interface 'tools-for-context)

          registry (create-registry {})]

      ;; Register tools with different capabilities
      (register! registry {:tool/id :tools/diagnostic
                           :tool/type :function
                           :tool/name "Diagnostic Tool"
                           :tool/capabilities #{:code/diagnostics}})

      (register! registry {:tool/id :tools/formatter
                           :tool/type :function
                           :tool/name "Format Tool"
                           :tool/capabilities #{:code/format}})

      (register! registry {:tool/id :tools/both
                           :tool/type :function
                           :tool/name "Both Tool"
                           :tool/capabilities #{:code/diagnostics :code/format}})

      ;; Query by capability
      (let [diagnostic-tools (tools-for-context registry #{:code/diagnostics})
            format-tools (tools-for-context registry #{:code/format})
            both-tools (tools-for-context registry #{:code/diagnostics :code/format})]

        (is (= 2 (count diagnostic-tools)) "2 tools have diagnostics capability")
        (is (= 2 (count format-tools)) "2 tools have format capability")
        (is (= 1 (count both-tools)) "1 tool has both capabilities")))))

(deftest builtin-tools-exist-test
  (testing "Built-in tool configurations exist in resources"
    (let [clojure-lsp-config (io/resource "tools/lsp/clojure.edn")]
      (is (some? clojure-lsp-config) "Built-in clojure.edn LSP config exists"))))

;------------------------------------------------------------------------------ Layer 3
;; Schema Validation Tests

(deftest tool-schema-validation-test
  (testing "Tool schema validates correctly"
    (require '[ai.miniforge.tool-registry.schema :as schema])
    (let [validate-tool (ns-resolve 'ai.miniforge.tool-registry.schema 'validate-tool)]

      ;; Valid tool
      (let [valid-tool {:tool/id :test/valid
                        :tool/type :function
                        :tool/name "Valid Tool"}
            result (validate-tool valid-tool)]
        (is (:valid? result) "Valid tool passes validation"))

      ;; Invalid tool (missing required fields)
      (let [invalid-tool {:tool/id :test/invalid}
            result (validate-tool invalid-tool)]
        (is (not (:valid? result)) "Invalid tool fails validation")))))

(deftest lsp-config-schema-validation-test
  (testing "LSP config schema validates correctly"
    (require '[ai.miniforge.tool-registry.schema :as schema])
    (let [validate-lsp-config (ns-resolve 'ai.miniforge.tool-registry.schema 'validate-lsp-config)]

      ;; Valid LSP config
      (let [valid-config {:lsp/command ["clojure-lsp"]
                          :lsp/languages #{"clojure"}}
            result (validate-lsp-config valid-config)]
        (is (:valid? result) "Valid LSP config passes validation"))

      ;; Invalid LSP config (missing command)
      (let [invalid-config {:lsp/languages #{"clojure"}}
            result (validate-lsp-config invalid-config)]
        (is (not (:valid? result)) "Invalid LSP config fails validation")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run conformance tests
  (clojure.test/run-tests 'conformance.tool-registry-conformance-test)

  :leave-this-here)
