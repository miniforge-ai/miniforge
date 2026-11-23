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
(ns jar-slim-test
  "`bb build:cli` drops compiled classes and native libraries from the
   CLI jar (babashka can load neither) and keeps source and resources."
  (:require
   [clojure.test :refer [deftest is testing]]
   [jar-slim :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} fixture-entries
  "[name content] pairs in jar order; a nil content is a directory."
  [["META-INF/MANIFEST.MF" "Manifest-Version: 1.0\n"]
   ["ai/" nil]
   ["ai/miniforge/" nil]
   ["ai/miniforge/x.clj" "(ns ai.miniforge.x)"]
   ["lib/" nil]
   ["lib/Foo.class" "cafebabe"]
   ["native/" nil]
   ["native/linux/" nil]
   ["native/linux/libx.so" "elf"]
   ["native/linux/libx.so.1" "elf"]
   ["config/y.edn" "{:a 1}"]
   ["META-INF/versions/9/z/Z.class" "cafebabe"]])

(defn ^{:stratum 0} write-jar!
  "Write `entries` ([name content] pairs) as a jar in a temp file."
  [entries]
  (let [f (java.io.File/createTempFile "jar-slim" ".jar")]
    (.deleteOnExit f)
    (with-open [out (java.util.zip.ZipOutputStream. (java.io.FileOutputStream. f))]
      (doseq [[entry-name content] entries]
        (.putNextEntry out (java.util.zip.ZipEntry. ^String entry-name))
        (when content (.write out (.getBytes ^String content "UTF-8")))
        (.closeEntry out)))
    (str f)))

(defn ^{:stratum 0} read-jar
  "[name content] pairs of the jar at `path`, in order; nil content for
   a directory."
  [path]
  (with-open [zip (java.util.zip.ZipFile. ^String path)]
    (mapv (fn [^java.util.zip.ZipEntry e]
            [(.getName e)
             (when-not (.isDirectory e)
               (slurp (.getInputStream zip e)))])
          (enumeration-seq (.entries zip)))))

(deftest ^{:stratum 0} drop-reason-test
  (testing "compiled classes, including multi-release ones"
    (is (= :class (sut/drop-reason "org/bouncycastle/X.class")))
    (is (= :class (sut/drop-reason "META-INF/versions/9/a/B.class"))))
  (testing "native libraries and Windows link artifacts"
    (doseq [n ["datalevin/dtlvnative/linux-x86_64/libdtlv.so"
               "libfoo.so.1.2" "a/libb.dylib" "a/libc.jnilib"
               "w/jniDTLV.dll" "w/jniDTLV.lib" "w/jniDTLV.exp" "w/jniDTLV.obj"]]
      (is (= :native (sut/drop-reason n)) n)))
  (testing "source, resources, metadata and directories are kept"
    (doseq [n ["ai/miniforge/cli/main.clj" "a/b.cljc" "babashka/pods.clj"
               "config/x.edn" "META-INF/MANIFEST.MF" "bb.edn" "SCI_VERSION"
               "dashboard.css" "a/" "so/" "notes.solution"]]
      (is (nil? (sut/drop-reason n)) n))))

(deftest ^{:stratum 0} kept-dirs-test
  (is (= ["a/" "a/b/"] (sut/kept-dirs ["a/" "a/b/" "c/"] ["a/b/x.clj"])))
  (is (= [] (sut/kept-dirs ["c/"] []))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} slim-jar-test
  (let [jar   (write-jar! fixture-entries)
        stats (sut/slim-jar! jar)]
    (testing "classes, native libraries and the directories they emptied are gone; the rest is unchanged and in order"
      (is (= [["META-INF/MANIFEST.MF" "Manifest-Version: 1.0\n"]
              ["ai/" nil]
              ["ai/miniforge/" nil]
              ["ai/miniforge/x.clj" "(ns ai.miniforge.x)"]
              ["config/y.edn" "{:a 1}"]]
             (read-jar jar))))
    (testing "the result reports what was dropped"
      (is (= {:class 2 :native 2} (:dropped stats)))
      (is (= 12 (:entries-before stats)))
      (is (= 5 (:entries-after stats)))
      (is (< (:bytes-after stats) (:bytes-before stats))))))
