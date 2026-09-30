;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.artifact.publication-codec :as codec]
            [ai.miniforge.artifact.publication-files :as files]
            [ai.miniforge.artifact.publication-record :as record-codec]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-directory [f]
  (let [root (.getCanonicalFile (.toFile (Files/createTempDirectory "artifact-publication-"
                                                                 (make-array FileAttribute 0))))]
    (try (f (.getPath root))
         (finally (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(defn- ^{:stratum 0} record []
  (artifact/build-artifact {:id (random-uuid) :type :manifest :version "1.0.0"
                            :content {:evidence/hash "verified"}}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} deferred-input-is-rejected-before-realization-test
  (with-directory
    (fn [directory]
      (let [visited (atom 0)
            content (map (fn [n] (swap! visited inc) n) (range 100000))
            value (assoc (record) :artifact/content content)
            result (with-redefs [codec/maximum-bytes 1024]
                     (artifact/publish! directory value))]
        (is (= :invalid-input (:anomaly/type result)))
        (is (zero? @visited))
        (is (empty? (seq (.listFiles (io/file directory)))))))))

(deftest ^{:stratum 1} overflow-aborts-java-output-stream-test
  (with-directory
    (fn [directory]
      (let [value (assoc (record) :artifact/content (apply str (repeat 10000 "x")))
            result (with-redefs [codec/maximum-bytes 1024]
                     (artifact/publish! directory value))]
        (is (= :invalid-input (:anomaly/type result)))
        (is (empty? (seq (.listFiles (io/file directory)))))))))

(deftest ^{:stratum 1} publication-is-immutable-and-uncached-test
  (with-directory
    (fn [directory]
      (let [value (record) id (:artifact/id value)]
        (is (nil? (artifact/read-published directory id)))
        (is (= value (artifact/publish! directory value)))
        (is (= value (artifact/read-published directory id)))
        (is (= value (artifact/publish! directory value)))
        (is (= :conflict (:anomaly/type (artifact/publish! directory (assoc value :artifact/content {})))))
        (is (= value (artifact/read-published directory id)))
        (spit (files/target directory id) "corrupt")
        (is (= :fault (:anomaly/type (artifact/read-published directory id))))))))

(deftest ^{:stratum 1} malformed-existing-record-is-a-fault-not-a-conflict-test
  (with-directory
    (fn [directory]
      (let [value (record) id (:artifact/id value)]
        (doseq [invalid [{} (assoc value :artifact/id (random-uuid))]]
          (with-open [output (io/output-stream (files/target directory id))]
            (.write output (record-codec/encode invalid)))
          (is (= :fault (:anomaly/type (artifact/read-published directory id))))
          (is (= :fault (:anomaly/type (artifact/publish! directory value)))))))))

(deftest ^{:stratum 1} invalid-input-and-unsafe-paths-refuse-publication-test
  (with-directory
    (fn [directory]
      (let [value (record)]
        (doseq [invalid [nil 42 {} (assoc value :artifact/id "not-a-uuid")]]
          (is (= :invalid-input (:anomaly/type (artifact/publish! directory invalid)))))
        (doseq [path [nil "" (str directory "/../" (.getName (io/file directory))) (str directory (char 0))]]
          (is (= :invalid-input (:anomaly/type (artifact/publish! path value))))
          (is (= :invalid-input (:anomaly/type (artifact/read-published path (:artifact/id value))))))
        (is (empty? (seq (.listFiles (io/file directory)))))))))

(deftest ^{:stratum 1} unconfirmed-publication-is-recoverable-with-identical-content-test
  (with-directory
    (fn [directory]
      (let [value (record) id (:artifact/id value)
            unconfirmed (with-redefs [files/confirm! (fn [_] (throw (java.io.IOException. "force failed")))]
                          (artifact/publish! directory value))]
        (is (anomaly/anomaly? unconfirmed))
        (is (= id (get-in unconfirmed [:anomaly/data :artifact/id])))
        (is (= value (artifact/read-published directory id)))
        (is (= value (artifact/publish! directory value)))
        (is (= 1 (count (.listFiles (io/file directory)))))))))

(deftest ^{:stratum 1} prepublication-failure-never-acknowledges-or-leaves-target-test
  (with-directory
    (fn [directory]
      (let [value (record)
            result (with-redefs [files/write! (fn [& _] (throw (AssertionError. "write failed")))]
                     (artifact/publish! directory value))]
        (is (= :fatal (:anomaly/type result)))
        (is (nil? (artifact/read-published directory (:artifact/id value))))
        (is (empty? (seq (.listFiles (io/file directory)))))))))

(deftest ^{:stratum 1} codec-refuses-oversize-or-nonportable-content-test
  (with-directory
    (fn [directory]
      (doseq [content [(Object.) (apply str (repeat 2048 "x"))]]
        (let [result (with-redefs [codec/maximum-bytes 1024]
                       (artifact/publish! directory (assoc (record) :artifact/content content)))]
          (is (anomaly/anomaly? result))))
      (is (empty? (seq (.listFiles (io/file directory))))))))

(deftest ^{:stratum 1} concurrent-publishers-never-replace-a-winner-test
  (with-directory
    (fn [directory]
      (let [value (record) start (promise)
            candidates [value (assoc value :artifact/content {:other true})]
            workers (mapv #(future @start (artifact/publish! directory %)) candidates)]
        (deliver start true)
        (let [results (mapv deref workers)]
          (is (= 1 (count (filter anomaly/anomaly? results))))
          (is (= (first (remove anomaly/anomaly? results))
                 (artifact/read-published directory (:artifact/id value)))))))))

(deftest ^{:stratum 1} interruption-is-preserved-at-publication-boundary-test
  (with-directory
    (fn [directory]
      (let [[result interrupted?]
            (with-redefs [files/write! (fn [& _] (throw (InterruptedException.)))]
              (let [result (artifact/publish! directory (record))]
                [result (Thread/interrupted)]))]
        (is (anomaly/anomaly? result))
        (is interrupted?)))))

(deftest ^{:stratum 1} symlink-target-is-never-followed-test
  (with-directory
    (fn [directory]
      (let [value (record) id (:artifact/id value)
            outside (io/file directory "existing")
            target (files/target directory id)]
        (spit outside "untouched")
        (Files/createSymbolicLink (.toPath target) (.toPath outside) (make-array FileAttribute 0))
        (is (anomaly/anomaly? (artifact/read-published directory id)))
        (is (anomaly/anomaly? (artifact/publish! directory value)))
        (is (= "untouched" (slurp outside)))))))

(deftest ^{:stratum 1} trailing-data-is-never-confirmed-test
  (doseq [suffix ["{}" "garbage"]]
    (with-directory
      (fn [directory]
        (let [value (record) id (:artifact/id value)]
          (is (= value (artifact/publish! directory value)))
          (spit (files/target directory id) suffix :append true)
          (is (= :fault (:anomaly/type (artifact/read-published directory id))))
          (is (= :fault (:anomaly/type (artifact/publish! directory value)))))))))

(deftest ^{:stratum 1} malformed-utf8-is-never-replaced-test
  (with-directory
    (fn [directory]
      (let [value (assoc (record) :artifact/content "x") id (:artifact/id value)
            bytes (byte-array (map #(if (= (int \x) %) (unchecked-byte 255) %) (record-codec/encode value)))]
        (with-open [output (io/output-stream (files/target directory id))] (.write output bytes))
        (is (= :fault (:anomaly/type (artifact/read-published directory id))))
        (is (= :fault (:anomaly/type (artifact/publish! directory value))))))))

(deftest ^{:stratum 1} relative-directory-is-not-a-durability-root-test
  (let [root (.toFile (Files/createTempDirectory (.toPath (io/file ".")) "artifact-relative-"
                                                (make-array FileAttribute 0)))]
    (try
      (is (not (files/safe-directory? (.getName root))))
      (is (= :invalid-input (:anomaly/type (artifact/publish! (.getName root) (record)))))
      (finally (io/delete-file root)))))

(deftest ^{:stratum 1} schema-valid-corruption-fails-integrity-confirmation-test
  (with-directory
    (fn [directory]
      (let [value (record) id (:artifact/id value)
            target (files/target directory id)]
        (is (= value (artifact/publish! directory value)))
        (spit target (str/replace (slurp target) "verified" "modified"))
        (is (= :fault (:anomaly/type (artifact/read-published directory id))))
        (is (= :fault (:anomaly/type (artifact/publish! directory value))))))))

(deftest ^{:stratum 1} collection-type-corruption-fails-wire-integrity-test
  (with-directory
    (fn [directory]
      (let [value (assoc (record) :artifact/content (list 1 2))
            id (:artifact/id value) target (files/target directory id)]
        (is (= value (artifact/publish! directory value)))
        (let [envelope (codec/decode (files/read-bytes target))
              changed (String. ^bytes (codec/encode (assoc value :artifact/content [1 2])) StandardCharsets/UTF_8)
              corrupted (assoc envelope :publication/wire changed)]
          (with-open [output (io/output-stream target)] (.write output (codec/encode corrupted)))
          (is (= corrupted (codec/decode (files/read-bytes target))))
          (is (= [1 2] (:artifact/content (codec/decode (.getBytes changed StandardCharsets/UTF_8)))))
          (is (nil? (record-codec/decode (files/read-bytes target)))))
        (is (= :fault (:anomaly/type (artifact/read-published directory id))))
        (is (= :fault (:anomaly/type (artifact/publish! directory value))))))))

(deftest ^{:stratum 1} cleanup-failure-retains-confirmed-publication-test
  (with-directory
    (fn [directory]
      (let [value (record)
            fail-cleanup! (fn [_] (throw (java.io.IOException. "cleanup failed")))
            result (with-redefs [files/delete-temporary! fail-cleanup!]
                     (artifact/publish! directory value))]
        (is (= :unavailable (:anomaly/type result)))
        (is (= value (get-in result [:anomaly/data :publication/confirmed-artifact])))
        (is (= value (artifact/read-published directory (:artifact/id value))))))))

(deftest ^{:stratum 1} cleanup-failure-never-masks-primary-failure-test
  (with-directory
    (fn [directory]
      (let [value (record)
            fail-cleanup! (fn [_] (throw (java.io.IOException. "cleanup failed")))
            fail-write! (fn [& _] (throw (AssertionError. "fatal write")))]
        (is (= value (artifact/publish! directory value)))
        (with-redefs [files/delete-temporary! fail-cleanup!]
          (let [conflict (artifact/publish! directory (assoc value :artifact/content {}))
                fatal (with-redefs [files/write! fail-write!] (artifact/publish! directory value))]
            (is (= :conflict (:anomaly/type conflict)))
            (is (= :fatal (:anomaly/type fatal)))
            (doseq [result [conflict fatal]]
              (is (= :unavailable (get-in result [:anomaly/data :publication/cleanup-failure :anomaly/type]))))))))))
