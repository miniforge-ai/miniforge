(ns ai.miniforge.evidence-bundle.hash-test
  "Tests for SHA-256 content hashing."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.interface :as evidence]))

;------------------------------------------------------------------------------ Layer 0
;; Content Hash Tests

(deftest content-hash-deterministic-test
  (testing "Returns consistent hash for same input"
    (let [content {:type :terraform-plan :body "resource aws_instance"}
          hash-1 (evidence/content-hash content)
          hash-2 (evidence/content-hash content)]
      (is (= hash-1 hash-2)))))

(deftest content-hash-uniqueness-test
  (testing "Returns different hash for different input"
    (let [content-a {:type :terraform-plan :body "resource aws_instance"}
          content-b {:type :terraform-plan :body "resource aws_s3_bucket"}
          hash-a (evidence/content-hash content-a)
          hash-b (evidence/content-hash content-b)]
      (is (not= hash-a hash-b)))))

(deftest content-hash-format-test
  (testing "Hash is a 64-char hex string (SHA-256)"
    (let [content {:foo "bar"}
          h (evidence/content-hash content)]
      (is (string? h))
      (is (= 64 (count h)))
      (is (re-matches #"[0-9a-f]{64}" h)))))

(deftest content-hash-string-input-test
  (testing "Handles string content"
    (let [h (evidence/content-hash "hello world")]
      (is (= 64 (count h)))
      (is (re-matches #"[0-9a-f]{64}" h)))))

(deftest content-hash-nil-input-test
  (testing "Handles nil content"
    (let [h (evidence/content-hash nil)]
      (is (= 64 (count h)))
      (is (re-matches #"[0-9a-f]{64}" h)))))
