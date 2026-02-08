(ns ai.miniforge.lsp-mcp-bridge.installer-test
  "Tests for LSP auto-installer (unit tests, no actual downloads)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.lsp-mcp-bridge.installer :as installer]))

;------------------------------------------------------------------------------ Platform detection

(deftest platform-key-test
  (testing "returns a valid platform string"
    (let [pk (installer/platform-key)]
      (is (string? pk))
      (is (re-matches #"(macos|linux|windows|unknown)-(aarch64|x86_64|.+)" pk)
          (str "Unexpected platform key: " pk)))))

;------------------------------------------------------------------------------ Binary resolution

(deftest bin-dir-test
  (testing "returns a path under home directory"
    (let [bd (installer/bin-dir)]
      (is (string? bd))
      (is (.endsWith bd "/.miniforge/bin")))))

(deftest which-test
  (testing "finds existing binary"
    ;; 'bb' must exist since we're running under it
    (let [result (installer/which "bb")]
      (is (some? result))
      (is (string? result))))

  (testing "returns nil for non-existent binary"
    (is (nil? (installer/which "nonexistent-binary-xyz-12345")))))

(deftest resolve-binary-test
  (testing "resolves existing binary from PATH"
    (let [result (installer/resolve-binary "bb")]
      (is (some? result))))

  (testing "returns nil for non-existent binary"
    (is (nil? (installer/resolve-binary "nonexistent-binary-xyz-12345")))))

;------------------------------------------------------------------------------ ensure-installed (unit, no downloads)

(deftest ensure-installed-already-on-path-test
  (testing "returns command unchanged when binary is on PATH"
    (let [result (installer/ensure-installed nil :lsp/test ["bb"])]
      (is (= ["bb"] (:command result)))
      (is (nil? (:error result))))))

(deftest ensure-installed-not-found-no-registry-test
  (testing "returns error when binary not found and no registry"
    (let [result (installer/ensure-installed nil :lsp/test ["nonexistent-xyz"])]
      (is (string? (:error result)))
      (is (.contains (:error result) "not found")))))
