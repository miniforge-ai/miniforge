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
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} unexpected-outputs
  "Missing, truncated and trailing acknowledgements cannot attest protection."
  [nil "verified" "verified\nunexpected\n"])

(def ^{:stratum 0 :private true} private-permissions
  "The child starts without group or world access before ACL injection."
  "rwx------")

(def ^{:stratum 0 :private true} denied-ace
  "A deny-only ACL must not be confused with an additive grant."
  "everyone deny writeextattr")

(def ^{:stratum 0 :private true} additive-ace
  "Add a grant while leaving the POSIX mode unchanged."
  "everyone allow readattr")

(defn- ^{:stratum 0} refuse-scratch! [& _]
  (throw (AssertionError.)))

(defn- ^{:stratum 0} read-with-missing-path [read-output missing args input]
  (read-output (conj args missing) input #{0}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-unexpected-acknowledgements! [root]
  (doseq [output unexpected-outputs]
    (with-redefs [acl/macos? (constantly true)
                  probe/read-output (constantly output)
                  fs/create-temp-dir refuse-scratch!]
      (is (anomaly/anomaly? (durability/verify-publication! root))))))

(defn- ^{:stratum 1} verify-native-metadata-error! [root]
  (let [read-output probe/read-output
        missing (str (fs/path (fs/real-path root) "missing"))
        read! (partial read-with-missing-path read-output missing)]
    (is (= :publication-verified (durability/verify-publication! root)))
    (with-redefs [probe/read-output read!]
      (is (= :unauthorized (:anomaly/type (durability/verify-publication! root)))))))

(defn- ^{:stratum 1} verify-ancestor-acl! [root]
  (let [directory (.toFile (fs/create-dir (fs/path root "child")
                                         {:posix-file-permissions private-permissions}))
        original-mode (support/mode root)]
    (support/set-test-ace! root denied-ace)
    (is (= :publication-verified (durability/verify-publication! directory)))
    (support/set-test-ace! root additive-ace)
    (is (= original-mode (support/mode root)))
    (is (anomaly/anomaly? (durability/verify-publication! directory)))
    (is (empty? (fs/list-dir directory)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-unexpected-acknowledgements-prevent-scratch-writes
  (support/with-root verify-unexpected-acknowledgements!))

(deftest ^{:stratum 2 :integration true} test-native-metadata-error-is-not-absence-of-an-acl
  (when (acl/macos?)
    (support/with-root verify-native-metadata-error!)))

(deftest ^{:stratum 2 :integration true} test-macos-ancestor-acl-overrides-mode-only-publication-check
  (when (acl/macos?)
    (support/with-root verify-ancestor-acl!)))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.macos-acl-integration-test))
