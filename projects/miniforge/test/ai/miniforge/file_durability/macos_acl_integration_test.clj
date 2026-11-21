;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.macos-acl-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.macos-acl :as acl]
            [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} set-test-ace! [directory ace]
  (process/check (process/process ["/bin/chmod" "+a" ace (str directory)])))

(deftest ^{:stratum 0} absent-or-unexpected-acknowledgements-prevent-scratch-writes
  (support/with-root
   (fn [root]
     (doseq [output [nil "verified" "verified\nunexpected\n"]]
       (with-redefs [acl/macos? (constantly true)
                     probe/read-output (constantly output)
                     fs/create-temp-dir (fn [& _] (throw (AssertionError.)))]
         (is (anomaly/anomaly? (durability/verify-publication! root))))))))

(deftest ^{:stratum 0} native-metadata-error-is-not-absence-of-an-acl
  (when (acl/macos?)
    (support/with-root
     (fn [root]
       (let [read-output probe/read-output
             missing (str (fs/path (fs/real-path root) "missing"))]
         (is (= :publication-verified (durability/verify-publication! root)))
         (with-redefs [probe/read-output (fn [args input] (read-output (conj args missing) input))]
           (is (= :unauthorized (:anomaly/type (durability/verify-publication! root))))))))))

;------------------------------------------------------------------------------ Layer 1

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

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.macos-acl-integration-test))
