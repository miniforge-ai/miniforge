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
(ns jar-slim
  "Drops the entries babashka can never use from the CLI uberjar.
   `bb uberjar` copies the whole project classpath, including the
   compiled classes and native libraries of JVM-only dependencies
   (datalevin, javacpp, bouncycastle, ...). Babashka loads only
   Clojure source and resources from a jar: it cannot load a .class
   file or a JNI library, so those entries only add size. Everything
   else (source, resources, META-INF data) is kept."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} native-extensions
  "Extensions of native libraries and their Windows link artifacts."
  #{"so" "dylib" "jnilib" "dll" "lib" "exp" "obj"})

(defn ^{:stratum 0} extension
  "Lower-cased extension of the last path segment of `entry-name`, or nil."
  [entry-name]
  (let [base (peek (str/split entry-name #"/"))]
    (when-let [i (some-> base (str/last-index-of "."))]
      (str/lower-case (subs base (inc i))))))

(defn ^{:stratum 0} kept-dirs
  "Directory entries (names ending in /) that still contain a kept file."
  [dir-names file-names]
  (let [parents (into #{}
                      (mapcat (fn [file-name]
                                (let [parts (butlast (str/split file-name #"/"))]
                                  (map #(str (str/join "/" (take % parts)) "/")
                                       (range 1 (inc (count parts)))))))
                      file-names)]
    (filterv parents dir-names)))

(defn ^{:stratum 0} copy-entry!
  "Copy `entry` of `zip` (a ZipFile) into `out` (a ZipOutputStream)."
  [^java.util.zip.ZipFile zip ^java.util.zip.ZipOutputStream out ^java.util.zip.ZipEntry entry]
  (let [copy (java.util.zip.ZipEntry. (.getName entry))]
    (.setTime copy (.getTime entry))
    (.putNextEntry out copy)
    (when-not (.isDirectory entry)
      (with-open [in (.getInputStream zip entry)]
        (io/copy in out)))
    (.closeEntry out)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} drop-reason
  "Why bb cannot use `entry-name`: :class for compiled classes, :native
   for native libraries (including versioned libfoo.so.1); nil to keep."
  [entry-name]
  (cond
    (str/ends-with? entry-name "/") nil
    (= "class" (extension entry-name)) :class
    (contains? native-extensions (extension entry-name)) :native
    (re-find #"\.so(\.\d+)+$" entry-name) :native
    :else nil))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} slim-jar!
  "Rewrite `jar` without the entries drop-reason rejects, and without
   directory entries left empty by that. Entry order is preserved.
   Returns {:entries-before n :entries-after n :dropped {reason n}
   :bytes-before n :bytes-after n}."
  [jar]
  (let [tmp    (str jar ".slim")
        before (fs/size jar)
        counts (with-open [zip (java.util.zip.ZipFile. (str jar))]
                 (let [entries (vec (enumeration-seq (.entries zip)))
                       names   (mapv #(.getName ^java.util.zip.ZipEntry %) entries)
                       dirs    (filter #(str/ends-with? % "/") names)
                       files   (remove #(or (str/ends-with? % "/") (drop-reason %)) names)
                       keep?   (into (set files) (kept-dirs dirs files))
                       kept    (filterv #(keep? (.getName ^java.util.zip.ZipEntry %)) entries)]
                   (with-open [out (java.util.zip.ZipOutputStream. (java.io.FileOutputStream. tmp))]
                     (doseq [entry kept]
                       (copy-entry! zip out entry)))
                   {:entries-before (count entries)
                    :entries-after (count kept)
                    :dropped (frequencies (keep drop-reason names))}))]
    (fs/move tmp jar {:replace-existing true})
    (assoc counts :bytes-before before :bytes-after (fs/size jar))))
