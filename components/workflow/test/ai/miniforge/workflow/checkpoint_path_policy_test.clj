;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-path-policy-test
  (:require [ai.miniforge.workflow.boundary.checkpoint-directory :as directory]
            [ai.miniforge.workflow.boundary.checkpoint-permissions :as permissions]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private private-mode "rwx------")

(def ^{:stratum 0} ^:private shared-mode "rwxr-xr-x")

(def ^{:stratum 0} ^:private unsafe-modes ["rwxrwx---" "rwxrwxrwx"])

(def ^{:stratum 0} ^:private sticky-world-writable-mode 01777)

(defn- ^{:stratum 0} mode [path] (fs/posix->str (fs/posix-file-permissions path)))

(defn- ^{:stratum 0} foreign-link-attributes [read! link path attributes options]
  (cond-> (read! path attributes options)
    (= (str link) (str path)) (assoc :owner "another-account")))

(defn- ^{:stratum 0} verify-trusted-alias [root]
  (let [target (fs/create-dir (fs/path root "target"))
        alias (fs/create-sym-link (fs/path root "alias") target)
        nested (fs/path alias "nested")]
    (is (= (fs/path (fs/real-path target) "nested") (directory/prepare! nested)))
    (is (= (fs/real-path target) (directory/existing! alias)))))

(defn- ^{:stratum 0} verify-writable-original-path [root]
  (let [target (fs/create-dir (fs/path root "target"))
        unsafe (fs/create-dir (fs/path root "unsafe"))
        alias (fs/create-sym-link (fs/path unsafe "alias") target)]
    (fs/set-posix-file-permissions unsafe "rwxrwxrwx")
    (is (thrown? IOException (directory/prepare! (fs/path alias "nested"))))
    (is (thrown? IOException (directory/existing! alias)))
    (is (empty? (fs/list-dir target)))))

(defn- ^{:stratum 0} verify-native-alias [root]
  (let [target (fs/create-dir (fs/path root "target"))
        alias (fs/create-sym-link (fs/path root "alias") target)]
    (with-redefs [permissions/posix? (constantly false)]
      (is (= (fs/path (fs/real-path target) "nested") (directory/prepare! (fs/path alias "nested")))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-new-directories [root]
  (let [nested (fs/path root "new" "nested")
        prepared (directory/prepare! nested)]
    (is (= (fs/real-path nested) prepared))
    (is (= private-mode (mode prepared)))
    (is (= private-mode (mode (fs/parent prepared))))))

(defn- ^{:stratum 1} verify-read-only-sharing [root]
  (fs/set-posix-file-permissions root shared-mode)
  (is (= (fs/real-path root) (directory/prepare! root)))
  (is (= shared-mode (mode root))))

(defn- ^{:stratum 1} verify-unsafe-leaves [root]
  (doseq [unsafe unsafe-modes]
    (fs/set-posix-file-permissions root unsafe)
    (is (thrown? IOException (directory/prepare! root)))
    (is (= unsafe (mode root))))
  (fs/set-posix-file-permissions root private-mode))

(defn- ^{:stratum 1} verify-foreign-alias [root]
  (let [target (fs/create-dir (fs/path root "target"))
        alias (fs/create-sym-link (fs/path root "alias") target)
        read! (partial foreign-link-attributes fs/read-attributes alias)]
    (with-redefs [fs/read-attributes read!]
      (is (thrown? IOException (directory/prepare! (fs/path alias "nested"))))
      (is (thrown? IOException (directory/existing! alias))))
    (is (empty? (fs/list-dir target)))))

(defn- ^{:stratum 1} verify-sticky-leaf [root]
  (java.nio.file.Files/setAttribute (fs/path root) "unix:mode" (int sticky-world-writable-mode)
                                   (make-array java.nio.file.LinkOption 0))
  (is (thrown? IOException (directory/prepare! root))))

(deftest ^{:stratum 1} trusted-aliases-are-resolved (fixture/call-with-posix-checkpoint-root verify-trusted-alias))

(deftest ^{:stratum 1} writable-original-ancestry-cannot-hide-behind-an-alias (fixture/call-with-posix-checkpoint-root verify-writable-original-path))

(deftest ^{:stratum 1} native-providers-also-return-resolved-directories (fixture/call-with-posix-checkpoint-root verify-native-alias))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} missing-directories-are-created-private (fixture/call-with-posix-checkpoint-root verify-new-directories))

(deftest ^{:stratum 2} existing-read-only-sharing-is-preserved (fixture/call-with-posix-checkpoint-root verify-read-only-sharing))

(deftest ^{:stratum 2} writable-leaves-are-refused-without-chmod (fixture/call-with-posix-checkpoint-root verify-unsafe-leaves))

(deftest ^{:stratum 2} foreign-aliases-are-refused-before-creation (fixture/call-with-posix-checkpoint-root verify-foreign-alias))

(deftest ^{:stratum 2} sticky-bit-does-not-make-a-checkpoint-directory-safe (fixture/call-with-posix-checkpoint-root verify-sticky-leaf))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.checkpoint-path-policy-test))
