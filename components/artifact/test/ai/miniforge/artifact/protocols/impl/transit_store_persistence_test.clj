;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.artifact.protocols.impl.transit-store-persistence-test
  "Disk persistence of the transit store: atomic file writes, close
   waiting for queued writes, index merge and link preservation across
   stores that share a directory, and recovery from an unreadable index."
  (:require [ai.miniforge.artifact.interface.protocols.artifact-store :as p]
            [ai.miniforge.artifact.protocols.impl.transit-store :as impl]
            [ai.miniforge.artifact.protocols.records.transit-store :as store]
            [ai.miniforge.logging.interface :as log]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cognitect.transit :as transit])
  (:import [java.io File]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Arrays]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-dir ^File []
  (.toFile (Files/createTempDirectory "transit-store-persistence-test"
                                      (make-array FileAttribute 0))))

(defn- ^{:stratum 0} delete-dir! [dir]
  (when (.exists (io/file dir))
    (doseq [f (reverse (file-seq (io/file dir)))]
      (io/delete-file f true))))

(defn- ^{:stratum 0} artifact [id]
  {:artifact/id id
   :artifact/type :code
   :artifact/version "1.0.0"
   :artifact/parents []
   :artifact/children []})

(defn- ^{:stratum 0} open-store
  ([dir] (open-store dir {}))
  ([^File dir opts] (store/create-transit-store (assoc opts :dir (.getPath dir)))))

(defn- ^{:stratum 0} artifacts-path [^File dir]
  (impl/artifacts-dir (.getPath dir)))

(defn- ^{:stratum 0} read-or-unreadable
  "Read a transit file; ::unreadable instead of throwing, so a corrupt
   file fails an assertion rather than erroring the test."
  [path]
  (try
    (impl/read-transit path)
    (catch Exception _ ::unreadable)))

(defn- ^{:stratum 0} on-disk-ids
  "Artifact ids in the index file, or ::unreadable when it is corrupt."
  [^File dir]
  (try
    (set (keys (impl/read-transit (impl/index-file-path (impl/artifacts-dir (.getPath dir))))))
    (catch Exception _ ::unreadable)))

(defn- ^{:stratum 0} file-names [dir]
  (set (map (fn [^File f] (.getName f)) (.listFiles (io/file dir)))))

(defn- ^{:stratum 0} logged-events [entries]
  (set (map :log/event @entries)))

(defn- ^{:stratum 0} truncate-to-half! [path]
  (let [p (.toPath (io/file path))
        bytes (Files/readAllBytes p)]
    (Files/write p (Arrays/copyOf bytes (quot (alength bytes) 2))
                 (make-array OpenOption 0))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-failed-write-keeps-previous-file
  (let [dir (temp-dir)
        path (str (.getPath dir) "/data.transit.json")]
    (try
      (impl/write-transit path {:version 1})
      (testing "a write that fails part-way leaves the previous content in place"
        (is (thrown? Exception
                     (with-redefs [transit/write (fn [_ _] (throw (ex-info "disk full" {})))]
                       (impl/write-transit path {:version 2}))))
        (is (= {:version 1} (read-or-unreadable path))))
      (testing "and leaves no temp file behind"
        (is (= #{"data.transit.json"} (file-names dir))))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-close-waits-for-queued-writes
  (let [dir (temp-dir)
        write impl/write-transit]
    (try
      (with-redefs [impl/write-transit (fn [path data] (Thread/sleep 300) (write path data))]
        (let [s (open-store dir)]
          (p/save s (artifact "a1"))
          (p/close s)))
      (testing "once close returns, the artifact file and the index are on disk"
        (is (= #{"a1"} (on-disk-ids dir)))
        (is (= (artifact "a1")
               (read-or-unreadable (impl/artifact-file-path (artifacts-path dir) "a1")))))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-close-gives-up-after-its-timeout
  (let [dir (temp-dir)
        release (promise)
        write impl/write-transit
        [logger entries] (log/collecting-logger)]
    (try
      (with-redefs [impl/write-transit (fn [path data] @release (write path data))]
        (let [s (open-store dir {:logger logger :close-timeout-ms 200})
              _ (p/save s (artifact "a1"))
              started (System/nanoTime)
              flushed? (p/close s)
              elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
          (testing "close returns false near its timeout while a write is stuck"
            (is (false? flushed?))
            (is (< elapsed-ms 5000)))
          (testing "and logs a warning"
            (is (contains? (logged-events entries) :artifact/store-close-timeout)))
          (deliver release true)
          (testing "the queued writes still land once the write unblocks"
            (is (true? (p/close s)))
            (is (= #{"a1"} (on-disk-ids dir))))))
      (finally
        (deliver release true)
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-stores-sharing-a-directory-keep-each-others-entries
  (let [dir (temp-dir)]
    (try
      ;; Both open before either saves and so hold the same empty index,
      ;; as two CLI processes started close together do.
      (let [a (open-store dir)
            b (open-store dir)]
        (p/save a (artifact "from-a"))
        (p/close a)
        (p/save b (artifact "from-b"))
        (p/close b))
      (testing "the later writer does not drop the earlier writer's entries"
        (is (= #{"from-a" "from-b"} (on-disk-ids dir))))
      (testing "a store opened afterwards finds both artifacts"
        (let [s (open-store dir)]
          (is (= #{"from-a" "from-b"} (set (map :artifact/id (p/query s {})))))
          (p/close s)))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-concurrent-saves-all-reach-the-index
  (let [dir (temp-dir)]
    (try
      (let [stores [(open-store dir) (open-store dir)]
            ids (mapv #(str "art-" %) (range 40))
            saves (doall (map-indexed (fn [i id]
                                        (future (p/save (nth stores (mod i 2)) (artifact id))))
                                      ids))]
        (run! deref saves)
        (run! p/close stores)
        (testing "every save from both stores is in the index on disk, and it parses"
          (is (= (set ids) (on-disk-ids dir)))))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-queued-index-writes-collapse-into-one
  (let [dir (temp-dir)
        release (promise)
        index-writes (atom 0)
        write impl/write-transit]
    (try
      ;; Hold the writer on its first write so all saves queue behind it.
      (with-redefs [impl/write-transit (fn [path data]
                                         @release
                                         (when (str/ends-with? path "/index.transit.json")
                                           (swap! index-writes inc))
                                         (write path data))]
        (let [s (open-store dir)
              ids (mapv #(str "art-" %) (range 20))]
          (run! #(p/save s (artifact %)) ids)
          (deliver release true)
          (p/close s)
          (testing "twenty queued index writes cost one write"
            (is (= 1 @index-writes)))
          (testing "and that write holds every save"
            (is (= (set ids) (on-disk-ids dir))))))
      (finally
        (deliver release true)
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-links-from-two-stores-both-reach-the-artifact-file
  (let [dir (temp-dir)
        parent-file (impl/artifact-file-path (artifacts-path dir) "parent")]
    (try
      (let [s (open-store dir)]
        (run! #(p/save s (artifact %)) ["parent" "c1" "c2"])
        (p/close s))
      ;; Both stores cache the unlinked parent before either links it.
      (let [a (open-store dir)
            b (open-store dir)]
        (run! #(p/load-artifact % "parent") [a b])
        (p/link a "parent" "c1")
        (p/close a)
        (p/link b "parent" "c2")
        (p/close b))
      (testing "the parent's file keeps both stores' links"
        (is (= #{"c1" "c2"} (set (:artifact/children (read-or-unreadable parent-file))))))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-link-made-during-a-save-survives-it
  (let [dir (temp-dir)
        paused (promise)
        resume (promise)
        add impl/add-to-index]
    (try
      (let [s (open-store dir)]
        (run! #(p/save s (artifact %)) ["parent" "child"])
        ;; Hold a re-save of the parent between its cache and index updates.
        (with-redefs [impl/add-to-index (fn [index a]
                                          (when-not (realized? paused)
                                            (deliver paused true)
                                            @resume)
                                          (add index a))]
          (let [saving (future (p/save s (assoc (artifact "parent") :artifact/version "2")))
                _ @paused
                linking (future (p/link s "parent" "child"))]
            (deref linking 300 nil)
            (deliver resume true)
            @saving
            @linking))
        (p/close s))
      (testing "the parent's file keeps the link"
        (is (= ["child"] (:artifact/children
                          (read-or-unreadable (impl/artifact-file-path (artifacts-path dir) "parent"))))))
      (finally
        (deliver resume true)
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-index-keeps-another-stores-newer-entry
  (let [dir (temp-dir)]
    (try
      (let [s (open-store dir)]
        (p/save s (artifact "x"))
        (p/close s))
      (let [a (open-store dir)
            b (open-store dir)]
        (p/save a (assoc (artifact "x") :artifact/version "2.0.0"))
        (p/close a)
        (p/save b (artifact "y"))
        (p/close b))
      (testing "a store that did not change x leaves x's newer index entry alone"
        (is (= "2.0.0" (get-in (read-or-unreadable (impl/index-file-path (artifacts-path dir)))
                               ["x" :artifact/version]))))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-resave-drops-fields-it-no-longer-has
  (let [dir (temp-dir)]
    (try
      (let [s (open-store dir)]
        (p/save s (assoc (artifact "x") :artifact/origin {:phase :plan}))
        (p/close s)
        (p/save s (artifact "x"))
        (p/close s))
      (testing "the file holds the re-saved artifact, without the removed field"
        (is (= (artifact "x") (read-or-unreadable (impl/artifact-file-path (artifacts-path dir) "x")))))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-resave-keeps-links
  (let [dir (temp-dir)]
    (try
      (let [s (open-store dir)]
        (run! #(p/save s (artifact %)) ["parent" "child"])
        (p/link s "parent" "child")
        (p/close s)
        (p/save s (artifact "parent"))
        (testing "in memory, where a children query matches the cached copy"
          (is (= ["child"] (:artifact/children (p/load-artifact s "parent"))))
          (is (= [["child"]] (map :artifact/children (p/query s {:artifact/children ["child"]})))))
        (p/close s))
      (testing "and in the file"
        (is (= ["child"] (:artifact/children
                          (read-or-unreadable (impl/artifact-file-path (artifacts-path dir) "parent"))))))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-unreadable-index-is-rebuilt-from-artifact-files
  (let [dir (temp-dir)
        index-path (impl/index-file-path (artifacts-path dir))
        [logger entries] (log/collecting-logger)]
    (try
      (let [s (open-store dir)]
        (p/save s (artifact "parent"))
        (p/save s (artifact "child"))
        (p/link s "parent" "child")
        (p/close s))
      ;; An index write cut off half-way, and a torn artifact file.
      (truncate-to-half! index-path)
      (spit (impl/artifact-file-path (artifacts-path dir) "torn") "[\"^ \",\"~:artifact/id")
      (let [s (open-store dir {:logger logger})]
        (testing "the store opens and finds the artifacts"
          (is (= #{"parent" "child"} (set (map :artifact/id (p/query s {}))))))
        (testing "links are read back from the artifact files"
          (is (= ["child"] (:artifact/children (p/load-artifact s "parent"))))
          (is (= ["parent"] (get-in (read-or-unreadable index-path) ["child" :artifact/parents]))))
        (testing "the rebuilt index is on disk and the unreadable one is kept aside"
          (is (= #{"parent" "child"} (on-disk-ids dir)))
          (is (= 1 (count (filter #(str/starts-with? % "index.transit.json.corrupt-")
                                  (file-names (artifacts-path dir)))))))
        (testing "with a warning naming the skipped file"
          (is (contains? (logged-events entries) :artifact/index-rebuilt))
          (is (= [["torn.transit.json"]]
                 (keep #(get-in % [:data :skipped]) @entries))))
        (p/close s))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-failed-recovery-keeps-the-index-in-place
  (let [dir (temp-dir)
        index-path (impl/index-file-path (artifacts-path dir))
        write impl/write-transit]
    (try
      (let [s (open-store dir)]
        (p/save s (artifact "a1"))
        (p/close s))
      (truncate-to-half! index-path)
      (testing "a recovery whose index write fails leaves the index file in place"
        (with-redefs [impl/write-transit (fn [path data]
                                           (when (= path index-path) (throw (ex-info "disk full" {})))
                                           (write path data))]
          (is (thrown? Exception (open-store dir))))
        (is (.exists (io/file index-path))))
      (testing "so the next open still recovers every artifact"
        (let [s (open-store dir)]
          (is (= #{"a1"} (set (map :artifact/id (p/query s {})))))
          (p/close s)))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 1} test-open-store-quarantines-an-index-gone-unreadable
  (let [dir (temp-dir)
        [logger entries] (log/collecting-logger)]
    (try
      (let [s (open-store dir {:logger logger})]
        (p/save s (artifact "a1"))
        (p/close s)
        (truncate-to-half! (impl/index-file-path (artifacts-path dir)))
        (p/save s (artifact "a2"))
        (p/close s))
      (testing "the unreadable index is kept aside, with a warning"
        (is (= 1 (count (filter #(str/starts-with? % "index.transit.json.corrupt-")
                                (file-names (artifacts-path dir))))))
        (is (contains? (logged-events entries) :artifact/index-rebuilt)))
      (testing "and the index on disk holds both artifacts"
        (is (= #{"a1" "a2"} (on-disk-ids dir))))
      (finally
        (delete-dir! dir)))))
