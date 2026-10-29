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
(ns ai.miniforge.lsp-mcp-bridge.installer-integrity-test
  "GitHub-release installs must fail when extraction fails or the
   download does not match its pinned sha256, and must work when $HOME
   or TMPDIR contains a space or a quote. Downloads are stubbed: each
   test writes the archive bytes itself."
  (:require
   [ai.miniforge.lsp-mcp-bridge.installer :as installer]
   [ai.miniforge.response.interface :as response]
   [babashka.fs :as fs]
   [babashka.process :as bp]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.security MessageDigest)
   (java.util.zip GZIPOutputStream ZipEntry ZipOutputStream)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} script "#!/bin/sh\necho ok\n")

(defn ^{:stratum 0} write-gzip!
  [path content]
  (with-open [out (GZIPOutputStream. (io/output-stream (str path)))]
    (.write out (.getBytes ^String content "UTF-8"))))

(defn ^{:stratum 0} write-zip!
  "Zip with one entry `entry-name` holding `content`."
  [path entry-name content]
  (with-open [out (ZipOutputStream. (io/output-stream (str path)))]
    (.putNextEntry out (ZipEntry. ^String entry-name))
    (.write out (.getBytes ^String content "UTF-8"))
    (.closeEntry out)))

(defn ^{:stratum 0} install!
  "Run `install-via-github` for `asset` with the bin dir and download
   temp dir under `base`. `write-archive!` gets the archive path the
   stubbed download must fill. Returns [result bin-dir]."
  [base asset write-archive!]
  (let [bin-dir  (str (fs/path base "bin dir's"))
        tmp-dir  (fs/path base "download tmp's")
        entry    {:github "example/tool" :version "v1" :binary "tool"
                  :assets {"test-platform" asset}}]
    (with-redefs [installer/bin-dir       (constantly bin-dir)
                  fs/create-temp-dir      (fn [_opts] (fs/create-dirs tmp-dir))
                  installer/download-file (fn [_url dest] (write-archive! dest) nil)]
      [(binding [*err* (java.io.StringWriter.)]
         (installer/install-via-github entry "test-platform"))
       bin-dir])))

(defn ^{:stratum 0} with-base-dir
  "Call `f` with a fresh temp dir whose name has a space and a quote."
  [f]
  (let [base (fs/create-temp-dir {:prefix "lsp it's "})]
    (try (f (str base))
         (finally (fs/delete-tree base)))))

(defn ^{:stratum 0} sha256-hex [path]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (fs/read-all-bytes (str path)))]
    (apply str (map #(format "%02x" %) digest))))

(deftest ^{:stratum 0} download-fails-on-http-error-status-test
  (testing "curl runs with --fail so an HTTP error page is not saved as the archive"
    (let [argv (atom nil)]
      (with-redefs [bp/sh (fn [cmd] (reset! argv cmd) {:exit 0})]
        (binding [*err* (java.io.StringWriter.)]
          (installer/download-file "https://example.test/tool.gz" "/tmp/tool.gz")))
      (is (some #{"--fail"} @argv) (pr-str @argv)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} gzip-install-with-space-and-quote-in-paths-test
  (testing "a gzip asset installs when the bin and temp paths contain a space and a quote"
    (with-base-dir
      (fn [base]
        (let [[result bin-dir] (install! base {:file "tool.gz" :extract :gzip}
                                         #(write-gzip! % script))
              installed (:installed result)]
          (is (not (response/anomaly-map? result)) (pr-str result))
          (is (= (str bin-dir "/tool") installed))
          (is (= script (some-> installed slurp)) "binary decompressed in full")
          (is (some-> installed fs/executable?)))))))

(deftest ^{:stratum 1} corrupt-gzip-fails-install-test
  (testing "a gzip asset that is not gzip fails the install and leaves no binary"
    (with-base-dir
      (fn [base]
        (let [[result bin-dir] (install! base {:file "tool.gz" :extract :gzip}
                                         #(spit (str %) "not gzip"))]
          (is (response/anomaly-map? result))
          (is (str/includes? (str (:anomaly/message result)) "Extraction failed"))
          (is (not (fs/exists? (str bin-dir "/tool")))))))))

(deftest ^{:stratum 1} corrupt-zip-fails-install-test
  (testing "unzip exiting non-zero fails the install"
    (with-base-dir
      (fn [base]
        (let [[result _] (install! base {:file "tool.zip" :extract :zip}
                                   #(spit (str %) "not a zip"))]
          (is (response/anomaly-map? result))
          (is (= "unzip" (:lsp-installer/step result)))
          (is (pos? (:lsp-installer/exit result 0))))))))

(deftest ^{:stratum 1} zip-without-binary-fails-install-test
  (testing "an archive that extracts but holds no binary fails the install"
    (with-base-dir
      (fn [base]
        (let [[result _] (install! base {:file "tool.zip" :extract :zip}
                                   #(write-zip! % "README" "no binary here"))]
          (is (response/anomaly-map? result))
          (is (str/includes? (str (:anomaly/message result)) "Binary not found")))))))

(deftest ^{:stratum 1} sha256-mismatch-fails-install-test
  (testing "a download whose sha256 differs from the pinned one is not installed"
    (with-base-dir
      (fn [base]
        (let [[result bin-dir] (install! base {:file "tool.gz" :extract :gzip
                                               :sha256 (apply str (repeat 64 "0"))}
                                         #(write-gzip! % script))]
          (is (response/anomaly-map? result))
          (is (str/includes? (str (:anomaly/message result)) "Checksum mismatch"))
          (is (= (apply str (repeat 64 "0")) (:lsp-installer/expected-sha256 result)))
          (is (not (fs/exists? (str bin-dir "/tool")))))))))

(deftest ^{:stratum 1} sha256-match-installs-test
  (testing "a download matching its pinned sha256 (any case) installs"
    (with-base-dir
      (fn [base]
        (let [fixture (str (fs/path base "fixture.gz"))
              _       (write-gzip! fixture script)
              pinned  (str/upper-case (sha256-hex fixture))
              [result _] (install! base {:file "tool.gz" :extract :gzip :sha256 pinned}
                                   #(fs/copy fixture % {:replace-existing true}))]
          (is (not (response/anomaly-map? result)) (pr-str result))
          (is (= script (some-> (:installed result) slurp))))))))

(deftest ^{:stratum 1} gzip-bad-header-closes-archive-stream-test
  (testing "a bad gzip header (thrown by the GZIPInputStream constructor) still closes the file"
    (with-base-dir
      (fn [base]
        (let [archive (str (fs/path base "tool.gz"))
              _       (spit archive "not gzip")
              raw     (java.io.FileInputStream. archive)
              orig    io/input-stream]
          (try
            (with-redefs [io/input-stream (fn [x & opts]
                                            (if (= archive (str x)) raw (apply orig x opts)))]
              (is (thrown? java.util.zip.ZipException
                           (installer/extract-gzip archive (str (fs/path base "tool"))))))
            (is (not (.isOpen (.getChannel raw))) "the archive's file stream was closed")
            (finally (.close raw))))))))

(deftest ^{:stratum 1} directory-at-binary-path-fails-cleanly-test
  (testing "an archive that creates tool/README instead of a tool binary fails with its own
            error, and the directory is removed"
    (with-base-dir
      (fn [base]
        (let [[result bin-dir] (install! base {:file "tool.zip" :extract :zip}
                                         #(write-zip! % "tool/README" "not a binary"))]
          (is (response/anomaly-map? result))
          (is (str/includes? (str (:anomaly/message result)) "Binary not found")
              (pr-str (:anomaly/message result)))
          (is (not (fs/exists? (str bin-dir "/tool") {:nofollow-links true}))))))))

(deftest ^{:stratum 1} cleanup-failure-does-not-mask-install-error-test
  (testing "when cleanup throws, the install's own anomaly is returned with the cleanup error on it"
    (with-base-dir
      (fn [base]
        (let [[result _] (with-redefs [fs/delete-tree (fn [& _]
                                                        (throw (java.io.IOException. "cleanup failed")))]
                           (install! base {:file "tool.zip" :extract :zip}
                                     #(spit (str %) "not a zip")))]
          (is (response/anomaly-map? result))
          (is (= "unzip" (:lsp-installer/step result)) "the install's own error is kept")
          (is (= "cleanup failed" (:lsp-installer/cleanup-error result))))))))

(deftest ^{:stratum 1} cleanup-removes-symlink-without-following-it-test
  (testing "a symlink the archive put at the binary path is removed; its target is not touched"
    (with-base-dir
      (fn [base]
        (let [outside (str (fs/create-dirs (fs/path base "outside")))
              staging (str (fs/create-dirs (fs/path base "staging")))
              _       (spit (str outside "/keep") "keep")
              _       (fs/create-sym-link (fs/path staging "tool") outside)
              [result bin-dir] (install! base {:file "tool.tar.gz" :extract :tar-gz}
                                         #(bp/sh ["tar" "czf" (str %) "-C" staging "tool"]))]
          (is (response/anomaly-map? result))
          (is (not (fs/exists? (str bin-dir "/tool") {:nofollow-links true})))
          (is (fs/exists? (str outside "/keep")) "symlink target untouched"))))))

(deftest ^{:stratum 1} resolve-binary-requires-regular-executable-file-test
  (testing "resolve-binary accepts ~/.miniforge/bin/<name> only as a regular executable file"
    (with-base-dir
      (fn [base]
        (let [bin  (str (fs/path base "bin"))
              tool "lsp-integrity-probe-tool"
              path (str bin "/" tool)]
          (fs/create-dirs (fs/path path "sub"))
          (with-redefs [installer/bin-dir (constantly bin)]
            (is (nil? (installer/resolve-binary tool)) "a directory is not a binary")
            (fs/delete-tree path)
            (spit path script)
            (is (nil? (installer/resolve-binary tool)) "a non-executable file is not a binary")
            (fs/set-posix-file-permissions path "rwxr-xr-x")
            (is (= path (installer/resolve-binary tool)))))))))
