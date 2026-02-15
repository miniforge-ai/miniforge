(ns ai.miniforge.config.digest-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.config.digest :as digest]))

(deftest sha256-hex-test
  (testing "produces consistent hex digest with sha256: prefix"
    (let [result (digest/sha256-hex "hello")]
      (is (string? result))
      (is (.startsWith result "sha256:"))
      (is (= 71 (count result)))  ; "sha256:" (7) + 64 hex chars
      ;; Known SHA-256 of "hello"
      (is (= "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
             result))))

  (testing "different inputs produce different digests"
    (is (not= (digest/sha256-hex "hello")
              (digest/sha256-hex "world"))))

  (testing "same input produces same digest"
    (is (= (digest/sha256-hex "test content")
           (digest/sha256-hex "test content"))))

  (testing "empty string has a valid digest"
    (let [result (digest/sha256-hex "")]
      (is (.startsWith result "sha256:")))))

(deftest load-digest-manifest-test
  (testing "loads digest manifest from classpath"
    (let [manifest (digest/load-digest-manifest)]
      (is (map? manifest))
      (is (contains? manifest :readiness))
      (is (contains? manifest :risk))
      (is (contains? manifest :tiers))
      (is (contains? manifest :knowledge-safety))
      (is (every? #(.startsWith % "sha256:") (vals manifest))))))

(deftest verify-governance-file-test
  (testing "returns :ok for matching content"
    (let [manifest (digest/load-digest-manifest)
          content (slurp (io/resource "config/governance/readiness.edn"))]
      (when (and manifest (:readiness manifest))
        (is (= :ok (digest/verify-governance-file :readiness content))))))

  (testing "returns :mismatch for tampered content"
    (let [manifest (digest/load-digest-manifest)]
      (when manifest
        (is (= :mismatch (digest/verify-governance-file :readiness "tampered content"))))))

  (testing "returns :no-entry for unknown key"
    (let [manifest (digest/load-digest-manifest)]
      (when manifest
        (is (= :no-entry (digest/verify-governance-file :nonexistent "anything")))))))
