;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-alias-chain-test
  (:require [ai.miniforge.workflow.boundary.checkpoint-directory :as directory]
            [ai.miniforge.workflow.boundary.checkpoint-directory-policy :as policy]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private root-account "root")

(def ^{:stratum 0} ^:private sticky-world-writable-mode 01777)

(defn- ^{:stratum 0} verify-unsafe-hop [root]
  (let [target (fs/create-dir (fs/path root "target"))
        unsafe (fs/create-dir (fs/path root "unsafe"))
        _hop (fs/create-sym-link (fs/path unsafe "hop") target)
        alias (fs/create-sym-link (fs/path root "alias") (fs/path "unsafe" "hop"))]
    (fs/set-posix-file-permissions unsafe "rwxrwxrwx")
    (is (thrown? IOException (directory/prepare! (fs/path alias "nested"))))
    (is (thrown? IOException (directory/existing! alias)))
    (is (empty? (fs/list-dir target)))))

(defn- ^{:stratum 0} verify-trusted-hops [root]
  (let [target (fs/create-dir (fs/path root "target"))
        _hop (fs/create-sym-link (fs/path root "hop") (fs/path "target"))
        alias (fs/create-sym-link (fs/path root "alias") (fs/path "hop"))]
    (is (= (fs/real-path target) (directory/existing! alias)))
    (is (= (fs/path (fs/real-path target) "nested")
           (directory/prepare! (fs/path alias "nested"))))))

(defn- ^{:stratum 0} verify-cycle [root]
  (let [alias (fs/create-sym-link (fs/path root "alias") (fs/path "." "alias"))]
    (is (thrown? IOException (directory/prepare! alias)))
    (is (thrown? IOException (directory/existing! alias)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-root-owner [_root]
  (let [original (System/getProperty "user.name")]
    (try
      (System/setProperty "user.name" root-account)
      (is (true? (#'policy/trusted-owner? root-account)))
      (finally
        (if original (System/setProperty "user.name" original) (System/clearProperty "user.name"))))))

(defn- ^{:stratum 1} verify-sticky-hop [root]
  (let [sticky (fs/create-dir (fs/path root "sticky"))
        _hop (fs/create-sym-link (fs/path root "hop") sticky)
        alias (fs/create-sym-link (fs/path root "alias") (fs/path "hop"))]
    (java.nio.file.Files/setAttribute sticky "unix:mode" (int sticky-world-writable-mode)
                                     (make-array java.nio.file.LinkOption 0))
    (is (= (fs/path (fs/real-path sticky) "private")
           (directory/prepare! (fs/path alias "private"))))
    (is (= (fs/path (fs/real-path sticky) "private")
           (directory/existing! (fs/path alias "private"))))
    (is (thrown? IOException (directory/existing! alias)))
    (is (thrown? IOException (directory/prepare! alias)))))

(deftest ^{:stratum 1} intermediate-writable-targets-are-refused
  (fixture/call-with-posix-checkpoint-root verify-unsafe-hop))

(deftest ^{:stratum 1} trusted-relative-chains-remain-supported
  (fixture/call-with-posix-checkpoint-root verify-trusted-hops))

(deftest ^{:stratum 1} relative-link-cycles-terminate-without-creation
  (fixture/call-with-posix-checkpoint-root verify-cycle))

(defn- ^{:stratum 1} verify-sticky-parent-traversal [root]
  (let [sticky (fs/create-dir (fs/path root "sticky"))
        alias (fs/create-sym-link (fs/path root "alias") sticky)
        requested (fs/path alias "unused" ".." "private")
        expected (fs/path (fs/real-path sticky) "private")]
    (java.nio.file.Files/setAttribute sticky "unix:mode" (int sticky-world-writable-mode)
                                     (make-array java.nio.file.LinkOption 0))
    (is (= expected (directory/prepare! requested)))
    (is (= expected (directory/existing! requested)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} parent-traversal-through-a-sticky-alias-preserves-ancestor-semantics
  (fixture/call-with-posix-checkpoint-root verify-sticky-parent-traversal))

(deftest ^{:stratum 2} root-identity-does-not-create-duplicate-owner-errors
  (fixture/call-with-posix-checkpoint-root verify-root-owner))

(deftest ^{:stratum 2} sticky-targets-are-allowed-only-as-external-ancestors
  (fixture/call-with-posix-checkpoint-root verify-sticky-hop))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.checkpoint-alias-chain-test))
