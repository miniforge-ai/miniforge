;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.macos-acl-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.macos-acl :as acl]
            [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} directory-line "drwx------   3 501 20 96 Jan 1 12:00 /a")

(def ^{:stratum 0 :private true} acl-line "drwx------+  3 501 20 96 Jan 1 12:00 /b")

(def ^{:stratum 0 :private true} deny-line " 0: group:everyone inherited deny delete,write")

(def ^{:stratum 0 :private true} allow-line " 1: group:everyone allow readattr")

(defn- ^{:stratum 0} listing [& lines] (str/join "\n" lines))

(defn- ^{:stratum 0} set-test-ace! [directory ace]
  (process/check (process/process ["/bin/chmod" "+a" ace (str directory)])))

(deftest ^{:stratum 0} unreadable-macos-acls-prevent-publication-before-scratch-writes
  (support/with-root
   (fn [root]
     (with-redefs [acl/macos? (constantly true)
                   probe/read-output (constantly nil)
                   fs/create-temp-dir (fn [& _] (throw (AssertionError.)))]
       (is (anomaly/anomaly? (durability/verify-publication! root)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} complete-listing-may-contain-no-acls-or-denials
  (is (acl/non-granting-listing? (listing directory-line acl-line deny-line) 2))
  (is (acl/non-granting-listing? (listing acl-line deny-line directory-line) 2))
  (is (acl/non-granting-listing? directory-line 1)))

(deftest ^{:stratum 1} a-grant-or-incomplete-listing-is-not-proof
  (is (not (acl/non-granting-listing? (listing acl-line deny-line allow-line) 1)))
  (is (not (acl/non-granting-listing? (listing directory-line allow-line) 1)))
  (is (not (acl/non-granting-listing? (listing acl-line " 0: unknown entry") 1)))
  (is (not (acl/non-granting-listing? (listing deny-line directory-line) 1)))
  (is (not (acl/non-granting-listing? (listing directory-line acl-line) 2)))
  (is (not (acl/non-granting-listing? (listing directory-line directory-line) 1)))
  (is (not (acl/non-granting-listing? directory-line 2)))
  (is (not (acl/non-granting-listing? nil 1))))

(deftest ^{:stratum 1} actual-macos-ancestor-acl-overrides-mode-only-publication-check
  (when (acl/macos?)
    (support/with-root
     (fn [root]
       (let [directory (.toFile (fs/create-dir (fs/path root "child")
                                              {:posix-file-permissions "rwx------"}))
             original-mode (support/mode root)]
         (set-test-ace! root "everyone deny writeextattr")
         (is (= :publication-verified (durability/verify-publication! directory)))
         (set-test-ace! root "everyone allow readattr")
         (is (= original-mode (support/mode root)))
         (is (anomaly/anomaly? (durability/verify-publication! directory)))
         (is (empty? (fs/list-dir directory))))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.macos-acl-test))
