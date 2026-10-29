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
(ns ai.miniforge.gate.format-test
  "Tests for the LSP format gate.

   Regression coverage: `check-format` must return the canonical
   `response/success` shape AND `gate/passed?` must accept it.
   Earlier the gate machinery only checked `(:passed? result)` and
   `response/success` produces `{:status :success}` with no `:passed?`
   key, so every implement phase tripped a spurious :format failure
   even when nothing was wrong. Both halves of that contract are
   exercised here."
  (:require
   [ai.miniforge.gate.format :as format-gate]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.tool-registry.interface :as tool-registry]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} check-format-returns-canonical-success
  (testing "check-format returns response/success and gate/passed? accepts it"
    (let [artifact {:code/files [{:path "src/foo.clj"}]}
          result (format-gate/check-format artifact {})]
      (is (response/success? result)
          "check-format must return the canonical response/success shape")
      (is (gate/passed? (assoc result :gate :format))
          "gate/passed? must accept the canonical shape returned by check-format")))

  (testing "check-format passes even when no files are formattable"
    (let [result (format-gate/check-format {:code/files []} {})]
      (is (response/success? result))
      (is (gate/passed? (assoc result :gate :format)))
      (is (= [] (get-in result [:output :formattable-files]))))))

(deftest ^{:stratum 0} check-format-reports-formattable-files
  (testing "Formattable files are listed in the result output"
    (let [artifact {:code/files [{:path "a.clj"} {:path "b.md"}]}
          result (format-gate/check-format artifact {})]
      (is (response/success? result))
      (is (vector? (get-in result [:output :formattable-files]))))))

(deftest ^{:stratum 0} repair-format-applies-lsp-text-edits
  (testing "repair-format writes successful LSP format edits to disk"
    (let [dir (java.nio.file.Files/createTempDirectory
               "format-gate-test" (make-array java.nio.file.attribute.FileAttribute 0))
          src-dir (io/file (.toFile dir) "src")
          file (io/file src-dir "foo.clj")
          registry ::registry
          tool {:tool/id :lsp/clojure
                :tool/config {:lsp/file-patterns ["**/*.clj"]}}]
      (.mkdirs src-dir)
      (spit file "(defn x []\n  1)\n")
      (with-redefs [tool-registry/find-tools (fn [_ _] [tool])
                    tool-registry/get-lsp-client (fn [_ _] ::client)
                    tool-registry/format-document
                    (fn [_ _ _]
                      {:success? true
                       :result [{:range {:start {:line 0 :character 0}
                                         :end {:line 1 :character 4}}
                                 :newText "(defn x []\n  2)"}]})]
        (let [result (format-gate/repair-format
                      {:code/files [{:path "src/foo.clj"}]}
                      []
                      {:execution/worktree-path (.getPath (.toFile dir))
                       :tool-registry registry})]
          (is (response/success? result))
          (is (= ["src/foo.clj"] (get-in result [:output :formatted])))
          (is (= "(defn x []\n  2)\n" (slurp file))))))))

(defn- ^{:stratum 0} repair-with-edit!
  "Run repair-format over `rel-path` under `root`, with an LSP stub that
   rewrites the second line of `(defn x []\\n  1)` to return 2."
  [root rel-path]
  (with-redefs [tool-registry/find-tools (fn [_ _] [{:tool/id :lsp/clojure
                                                     :tool/config {:lsp/file-patterns ["**/*.clj"]}}])
                tool-registry/get-lsp-client (fn [_ _] ::client)
                tool-registry/format-document
                (fn [_ _ _]
                  {:success? true
                   :result [{:range {:start {:line 0 :character 0}
                                     :end {:line 1 :character 4}}
                             :newText "(defn x []\n  2)"}]})]
    (format-gate/repair-format {:code/files [{:path rel-path}]}
                               []
                               {:execution/worktree-path (str root)
                                :tool-registry ::registry})))

(defn- ^{:stratum 0} temp-src-dir
  "A fresh temp worktree root with an empty src/ under it. The stub's
   file pattern **/*.clj needs at least one directory segment."
  []
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "format-gate-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.mkdirs (io/file root "src"))
    root))

(defn- ^{:stratum 0} fmt-temp-files
  [^java.io.File dir]
  (->> (.listFiles dir)
       (map #(.getName ^java.io.File %))
       (filter #(str/starts-with? % ".fmt-"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} repair-format-keeps-the-file-mode
  (testing "an atomic rewrite keeps the original permission bits"
    (let [root (temp-src-dir)
          file (io/file root "src" "run.clj")
          path (.toPath file)
          mode (java.nio.file.attribute.PosixFilePermissions/fromString "rwxr-xr-x")]
      (spit file "(defn x []\n  1)\n")
      (when (.supportsFileAttributeView (java.nio.file.Files/getFileStore path) "posix")
        (java.nio.file.Files/setPosixFilePermissions path mode)
        (is (response/success? (repair-with-edit! root "src/run.clj")))
        (is (= "(defn x []\n  2)\n" (slurp file)))
        (is (= mode (java.nio.file.Files/getPosixFilePermissions
                     path (make-array java.nio.file.LinkOption 0)))
            "the temp file's 0600 must not replace the source's mode")))))

(deftest ^{:stratum 1} repair-format-writes-through-a-symlink
  (testing "a symlinked source keeps its link; the file it points at is rewritten"
    (let [root (temp-src-dir)
          real (io/file root "src" "real.clj")
          link (io/file root "src" "link.clj")]
      (spit real "(defn x []\n  1)\n")
      (java.nio.file.Files/createSymbolicLink
       (.toPath link) (.toPath real) (make-array java.nio.file.attribute.FileAttribute 0))
      (is (response/success? (repair-with-edit! root "src/link.clj")))
      (is (java.nio.file.Files/isSymbolicLink (.toPath link)) "the link is not replaced by a regular file")
      (is (= "(defn x []\n  2)\n" (slurp real))))))

(deftest ^{:stratum 1} repair-format-partial-write-keeps-the-original
  (testing "a write that fails part-way leaves the source as it was"
    (let [root (temp-src-dir)
          file (io/file root "src" "foo.clj")
          real-spit spit]
      (spit file "(defn x []\n  1)\n")
      (with-redefs [spit (fn [f content & opts]
                           ;; Half the bytes reach the temp file, then the disk fills.
                           (apply real-spit f (subs content 0 (quot (count content) 2)) opts)
                           (throw (java.io.IOException. "No space left on device")))]
        (let [result (repair-with-edit! root "src/foo.clj")]
          (is (response/success? result))
          (is (empty? (get-in result [:output :formatted]))
              "a file whose write failed is not reported as formatted")))
      (is (= "(defn x []\n  1)\n" (slurp file)) "the original bytes are intact")
      (is (empty? (fmt-temp-files (io/file root "src"))) "no temp file is left behind"))))

(deftest ^{:stratum 1} repair-format-writes-under-the-original-mode
  (testing "the temp file already has a 0600 source's mode when the content is written"
    (let [root (temp-src-dir)
          file (io/file root "src" "secret.clj")
          path (.toPath file)
          owner-only (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------")
          mode-at-write (atom nil)
          real-spit spit]
      (spit file "(defn x []\n  1)\n")
      (when (.supportsFileAttributeView (java.nio.file.Files/getFileStore path) "posix")
        (java.nio.file.Files/setPosixFilePermissions path owner-only)
        (with-redefs [spit (fn [f & args]
                             (reset! mode-at-write
                                     (java.nio.file.Files/getPosixFilePermissions
                                      (.toPath (io/file f)) (make-array java.nio.file.LinkOption 0)))
                             (apply real-spit f args))]
          (is (response/success? (repair-with-edit! root "src/secret.clj"))))
        (is (= owner-only @mode-at-write))
        (is (= owner-only (java.nio.file.Files/getPosixFilePermissions
                           path (make-array java.nio.file.LinkOption 0))))))))
