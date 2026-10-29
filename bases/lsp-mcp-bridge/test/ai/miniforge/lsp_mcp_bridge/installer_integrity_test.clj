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
  "Run `install-via-github` for `asset` with the bin dir under `base` and
   the staging dir (inside the bin dir) named with a space and a quote.
   `write-archive!` gets the archive path the stubbed download must
   fill. Returns [result bin-dir]."
  [base asset write-archive!]
  (let [bin-dir  (str (fs/path base "bin dir's"))
        entry    {:github "example/tool" :version "v1" :binary "tool"
                  :assets {"test-platform" asset}}]
    (with-redefs [installer/bin-dir       (constantly bin-dir)
                  fs/create-temp-dir      (fn [opts] (fs/create-dirs (fs/path (:dir opts) (str "staging tmp's " (random-uuid)))))
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

(defn ^{:stratum 0} with-stale-binary
  "Call `f` with `base` after placing a non-executable `tool` holding
   \"stale\" at the bin path install! uses."
  [base f]
  (let [path (str (fs/path base "bin dir's" "tool"))]
    (fs/create-dirs (fs/parent path))
    (spit path "stale")
    (f path)))

(defn ^{:stratum 0} write-tool-tgz!
  "Write a tar.gz under `base` holding bin/tool (prints its own $0) and
   script/x.lua; return its path."
  [base]
  (let [src (str (fs/create-dirs (fs/path base "src")))
        tgz (str (fs/path base "dist.tar.gz"))]
    (fs/create-dirs (fs/path src "bin"))
    (fs/create-dirs (fs/path src "script"))
    (spit (str src "/bin/tool") "#!/bin/sh\necho \"$0\"\n")
    (fs/set-posix-file-permissions (str src "/bin/tool") "rwxr-xr-x")
    (spit (str src "/script/x.lua") "return 1")
    (bp/sh ["tar" "czf" tgz "-C" src "bin" "script"])
    tgz))

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

(deftest ^{:stratum 1} failed-reinstall-leaves-existing-target-untouched-test
  (testing "tar writing the binary and then failing (truncated archive) does not touch the target"
    (with-base-dir
      (fn [base]
        (with-stale-binary base
          (fn [target]
            (let [src   (str (fs/create-dirs (fs/path base "src")))
                  noise (byte-array 200000)
                  full  (str (fs/path base "full.tar.gz"))]
              (spit (str src "/tool") script)
              (fs/set-posix-file-permissions (str src "/tool") "rwxr-xr-x")
              (.nextBytes (java.util.Random. 1) noise)
              (fs/write-bytes (str src "/noise") noise)
              (bp/sh ["tar" "czf" full "-C" src "tool" "noise"])
              (let [bytes      (fs/read-all-bytes full)
                    [result _] (install! base {:file "tool.tar.gz" :extract :tar-gz}
                                         #(fs/write-bytes (str %) (java.util.Arrays/copyOf
                                                                   bytes (quot (alength bytes) 2))))]
                (is (response/anomaly-map? result))
                (is (= "stale" (slurp target)) "target content unchanged")
                (is (not (fs/executable? target)))))))))))

(deftest ^{:stratum 1} archive-without-binary-does-not-adopt-existing-file-test
  (testing "a verified archive without the binary fails; the stale file is not chmod'ed or reported"
    (with-base-dir
      (fn [base]
        (with-stale-binary base
          (fn [target]
            (let [[result _] (install! base {:file "tool.zip" :extract :zip}
                                       #(write-zip! % "README" "no binary here"))]
              (is (response/anomaly-map? result) (pr-str result))
              (is (= "stale" (slurp target)))
              (is (not (fs/executable? target))))))))))

(deftest ^{:stratum 1} archive-install-keeps-distribution-test
  (testing "a tar.gz install keeps the whole tree; the target launches the real binary inside it"
    (with-base-dir
      (fn [base]
        (let [src   (str (fs/create-dirs (fs/path base "src")))
              tgz   (str (fs/path base "dist.tar.gz"))
              asset {:file "tool.tar.gz" :extract :tar-gz :binary-in-archive "bin/tool"}
              lua   #(fs/path (fs/parent (fs/parent %)) "script" "x.lua")
              run   #(str/trim (:out (bp/sh [%])))]
          (fs/create-dirs (fs/path src "bin"))
          (fs/create-dirs (fs/path src "script"))
          (spit (str src "/bin/tool") "#!/bin/sh\necho \"$0\"\n")
          (fs/set-posix-file-permissions (str src "/bin/tool") "rwxr-xr-x")
          (spit (str src "/script/x.lua") "return 1")
          (bp/sh ["tar" "czf" tgz "-C" src "bin" "script"])
          (let [[result bin-dir] (install! base asset #(fs/copy tgz % {:replace-existing true}))
                target   (:installed result)
                launched (run target)]
            (is (= (str bin-dir "/tool") target) (pr-str result))
            (is (str/includes? launched "/.dist/") "execs the binary inside the distribution")
            (is (= launched (str (fs/real-path launched))) "by its real path, not via a link")
            (is (fs/exists? (lua launched)) "runtime file kept beside the real binary")
            (with-redefs [installer/bin-dir (constantly bin-dir)]
              (is (= target (installer/resolve-binary "tool"))))
            (testing "and a failed reinstall leaves that install intact"
              (let [bytes      (fs/read-all-bytes tgz)
                    [again _]  (install! base asset
                                         #(fs/write-bytes (str %) (java.util.Arrays/copyOf
                                                                   bytes (quot (alength bytes) 2))))]
                (is (response/anomaly-map? again))
                (is (= launched (run target)))
                (is (fs/exists? (lua launched)))))))))))

(deftest ^{:stratum 1} parent-dir-symlink-in-archive-is-rejected-test
  (testing "an archive whose bin/ is a symlink to ../.. cannot point bin/tool at the live target"
    (with-base-dir
      (fn [base]
        (with-stale-binary base
          (fn [target]
            (let [src (str (fs/create-dirs (fs/path base "src")))
                  tgz (str (fs/path base "evil.tar.gz"))]
              (fs/create-sym-link (fs/path src "bin") "../..")
              (bp/sh ["tar" "czf" tgz "-C" src "bin"])
              (let [[result _] (install! base {:file "tool.tar.gz" :extract :tar-gz
                                               :binary-in-archive "bin/tool"}
                                         #(fs/copy tgz % {:replace-existing true}))]
                (is (response/anomaly-map? result) (pr-str result))
                (is (= "stale" (slurp target)))
                (is (not (fs/executable? target)))))))))))

(defn ^{:stratum 1} install-tgz!
  "Install `tgz` as an archive asset. Returns [result tree bin-dir], where
   tree is the .dist dir the launcher runs from (nil when it failed)."
  [base tgz]
  (let [[result bin-dir] (install! base {:file "tool.tar.gz" :extract :tar-gz
                                         :binary-in-archive "bin/tool"}
                                   #(fs/copy tgz % {:replace-existing true}))
        launched (some-> (:installed result) vector bp/sh :out str/trim)]
    [result (some-> launched fs/parent fs/parent str) bin-dir]))

(deftest ^{:stratum 1} checksum-error-discards-staging-test
  (testing "an error while hashing fails the install as an anomaly and leaves no staging dir"
    (with-base-dir
      (fn [base]
        (let [[result bin-dir] (with-redefs [installer/file-sha256
                                             (fn [_] (throw (java.io.IOException. "read failed")))]
                                 (install! base {:file "tool.gz" :extract :gzip
                                                 :sha256 (apply str (repeat 64 "0"))}
                                           #(write-gzip! % script)))]
          (is (response/anomaly-map? result))
          (is (str/includes? (str (:anomaly/message result)) "read failed"))
          (is (not-any? #(str/starts-with? (str (fs/file-name %)) "staging tmp's")
                        (fs/list-dir bin-dir))))))))

(deftest ^{:stratum 1} concurrent-installs-of-one-binary-serialize-test
  (testing "a second install of the same binary waits for the first, then uses its result"
    (with-base-dir
      (fn [base]
        (let [bin-dir   (str (fs/path base "bin dir's"))
              entry     {:github "example/tool" :version "v1" :binary "tool"
                         :assets {"test-platform" {:file "tool.gz" :extract :gzip}}}
              entered   (java.util.concurrent.CountDownLatch. 1)
              release   (java.util.concurrent.CountDownLatch. 1)
              downloads (atom 0)
              b-thread  (promise)
              install   #(binding [*err* (java.io.StringWriter.)]
                           (installer/install-via-github entry "test-platform"))
              deadline  (+ (System/nanoTime) 10000000000)]
          (with-redefs [installer/bin-dir       (constantly bin-dir)
                        installer/download-file (fn [_url dest]
                                                  (swap! downloads inc)
                                                  (.countDown entered)
                                                  (.await release)
                                                  (write-gzip! dest script)
                                                  nil)]
            (let [a (future (install))
                  _ (.await entered)
                  b (future (deliver b-thread (Thread/currentThread)) (install))]
              ;; A is inside its download. Wait until B parks: on the install
              ;; lock when installs serialize, inside its own download when not.
              (while (and (not= "WAITING" (str (.getState ^Thread @b-thread)))
                          (< (System/nanoTime) deadline))
                (Thread/onSpinWait))
              (.countDown release)
              (is (= {:installed (str bin-dir "/tool")} @a))
              (is (= {:installed (str bin-dir "/tool")} @b))
              (is (= 1 @downloads) "the waiting install rechecked the target instead of downloading"))))))))

(deftest ^{:stratum 1} ensure-installed-passes-install-warnings-test
  (testing "ensure-installed keeps a successful install's warnings on its result"
    (with-base-dir
      (fn [base]
        (with-redefs [installer/bin-dir     (constantly base)
                      installer/run-install (constantly {:installed "/opt/lsp/tool"
                                                         :lsp-installer/warnings ["could not prune"]})]
          (is (= {:command ["/opt/lsp/tool" "--stdio"] :lsp-installer/warnings ["could not prune"]}
                 (installer/ensure-installed {:servers {:t {:tool-id :lsp/t :binary "lsp-warn-probe"}}}
                                             :lsp/t ["lsp-warn-probe" "--stdio"]))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} archive-installs-prune-to-current-and-previous-test
  (testing "trees live under .dist/<binary>/; all but the current and the previous one are pruned"
    (with-base-dir
      (fn [base]
        (let [tgz             (write-tool-tgz! base)
              [_ t1]          (install-tgz! base tgz)
              [_ t2]          (install-tgz! base tgz)
              [r3 t3 bin-dir] (install-tgz! base tgz)]
          (is (= (str (fs/real-path (fs/path bin-dir ".dist" "tool"))) (some-> t3 fs/parent str)))
          (is (not (contains? r3 :lsp-installer/warnings)) (pr-str r3))
          (is (fs/exists? t3) "current tree kept")
          (is (fs/exists? t2) "previous tree kept for a server still running from it")
          (is (not (fs/exists? t1)) "older tree pruned")
          (is (= 2 (count (filter fs/directory? (fs/list-dir (fs/path bin-dir ".dist" "tool")))))))))))

(deftest ^{:stratum 2} cleanup-errors-after-replace-are-warnings-test
  (testing "once the target is replaced, failing to delete staging or prune is a warning"
    (with-base-dir
      (fn [base]
        (let [tgz     (write-tool-tgz! base)
              [_ t1]  (install-tgz! base tgz)
              _       (install-tgz! base tgz)
              [r3 t3] (with-redefs [fs/delete-tree (fn [& _] (throw (java.io.IOException. "busy")))]
                        (install-tgz! base tgz))]
          (is (not (response/anomaly-map? r3)) (pr-str r3))
          (is (some? t3) "the new install runs")
          (is (= 2 (count (:lsp-installer/warnings r3))) "staging dir and the pruned tree")
          (is (fs/exists? t1) "its removal failed, so the oldest tree is still there"))))))

(deftest ^{:stratum 2} prune-keeps-recorded-previous-over-a-newer-orphan-test
  (testing "the tree kept beside the current one is the recorded previous install, not the newest dir"
    (with-base-dir
      (fn [base]
        (let [tgz            (write-tool-tgz! base)
              [_ t1]         (install-tgz! base tgz)
              [_ t2 bin-dir] (install-tgz! base tgz)
              orphan         (fs/create-dirs (fs/path bin-dir ".dist" "tool" "v1-interrupted"))
              [_ t3]         (install-tgz! base tgz)]
          (is (fs/exists? t3))
          (is (fs/exists? t2) "recorded previous install kept")
          (is (not (fs/exists? orphan)) "tree from an interrupted install pruned")
          (is (not (fs/exists? t1))))))))
