;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.channel :as channel]
            [ai.miniforge.file-durability.io :as file-io]
            [ai.miniforge.file-durability.publication-support :as support]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fail-io! [& _] (throw (IOException.)))

(defn- ^{:stratum 0} observe-open! [open! observed file options]
  (let [channel (open! file options)]
    (compare-and-set! observed nil channel)
    channel))

(defn- ^{:stratum 0} observe-force! [force! forced channel]
  (swap! forced conj channel)
  (force! channel))

(defn- ^{:stratum 0} publish-after-force! [forced temporary target]
  (is (= 1 (count @forced)))
  (support/move! temporary target))

(defn- ^{:stratum 0} verify-readonly [root]
  (when (support/posix? root)
    (let [{:keys [temporary target]} (support/files root)]
      (support/make-readonly! target)
      (is (identical? target (durability/publish-bytes! temporary support/candidate-bytes
                               (partial support/publish-readonly! temporary target))))
      (is (= support/readonly (support/mode target)))
      (is (= support/candidate (slurp target))))))

(defn- ^{:stratum 0} verify-symlink-refusal [root]
  (when (support/posix? root)
    (let [{:keys [temporary target]} (support/files root)
          called? (atom false)]
      (java.nio.file.Files/delete (.toPath temporary))
      (java.nio.file.Files/createSymbolicLink (.toPath temporary) (.toPath target)
                                             (make-array java.nio.file.attribute.FileAttribute 0))
      (is (anomaly/anomaly? (durability/publish-bytes! temporary support/candidate-bytes #(reset! called? true))))
      (is (false? @called?))
      (is (= support/previous (slurp target))))))

(defn- ^{:stratum 0} verify-existing-confirmation [root]
  (let [{:keys [target]} (support/files root)]
    (is (identical? target (durability/confirm! target)))
    (is (= support/previous (slurp target)))))

(defn- ^{:stratum 0} verify-complete-byte-replacement [root]
  (let [{:keys [temporary target]} (support/files root)]
    (spit temporary (str support/candidate support/previous))
    (is (identical? target (durability/publish-bytes! temporary support/candidate-bytes
                             (partial support/move! temporary target))))
    (is (= support/candidate (slurp target)))))

(deftest ^{:stratum 0} invalid-publication-callbacks-are-rejected
  (doseq [callback [nil {} :not-a-function]]
    (is (= :invalid-input (:anomaly/type (durability/publish-bytes! (java.io.File. "unused") support/candidate-bytes callback))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-prior-target [root]
  (let [{:keys [temporary target]} (support/files root)
        called? (atom false)]
    (with-redefs [channel/force! fail-io!]
      (is (anomaly/anomaly? (durability/publish-bytes! temporary support/candidate-bytes #(reset! called? true)))))
    (is (false? @called?))
    (is (= support/previous (slurp target)))))

(defn- ^{:stratum 1} verify-confirmation-refusal [root]
  (let [{:keys [temporary target]} (support/files root)
        observed (atom nil)
        open! (partial observe-open! channel/open! observed)]
    (with-redefs [file-io/sync-ancestry! fail-io!
                  channel/open! open!]
      (is (anomaly/anomaly? (durability/publish-bytes! temporary support/candidate-bytes
                               (partial support/move! temporary target)))))
    (is (false? (.isOpen ^java.nio.channels.FileChannel @observed)))
    (is (= support/candidate (slurp target)))))

(defn- ^{:stratum 1} verify-ordering [root]
  (let [{:keys [temporary target]} (support/files root)
        observed (atom nil)
        forced (atom [])
        open! (partial observe-open! channel/open! observed)
        force! (partial observe-force! channel/force! forced)]
    (with-redefs [channel/open! open!
                  channel/force! force!]
      (is (identical? target (durability/publish-bytes! temporary support/candidate-bytes
                               (partial publish-after-force! forced temporary target)))))
    (is (identical? (first @forced) (second @forced)))
    (is (false? (.isOpen ^java.nio.channels.FileChannel @observed)))
    (is (= support/candidate (slurp target)))))

(deftest ^{:stratum 1} readonly-publication-is-confirmed-without-reopening-for-write (support/with-root verify-readonly))

(deftest ^{:stratum 1} a-linked-temporary-is-refused-without-touching-the-linked-file (support/with-root verify-symlink-refusal))

(deftest ^{:stratum 1} confirming-existing-files-does-not-change-their-bytes (support/with-root verify-existing-confirmation))

(deftest ^{:stratum 1} complete-publication-does-not-retain-old-temporary-bytes (support/with-root verify-complete-byte-replacement))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} failed-first-force-does-not-invoke-publication (support/with-root verify-prior-target))

(deftest ^{:stratum 2} failed-directory-confirmation-does-not-report-success (support/with-root verify-confirmation-refusal))

(deftest ^{:stratum 2} publication-forces-the-same-held-file-before-and-after-rename (support/with-root verify-ordering))

(comment
  (clojure.test/run-tests 'ai.miniforge.file-durability.publication-test))
