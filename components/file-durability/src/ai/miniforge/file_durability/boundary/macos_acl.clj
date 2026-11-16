;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.macos-acl
  "Additional macOS guard for canonical paths already checked by POSIX policy."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [babashka.fs :as fs]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} native-command
  "Read an immutable packaged script from stdin and pass paths only as arguments."
  ["/usr/bin/osascript" "-l" "JavaScript" "-"])

(def ^{:stratum 0 :private true} native-script
  "Vendored read-only Darwin ACL metadata probe, loaded only on macOS."
  (delay (slurp (io/resource "config/file-durability/native/macos-acl.js"))))

(def ^{:stratum 0 :private true} verified-output
  "One acknowledgement for the complete path and every requested ancestor."
  "verified\n")

(defn ^{:stratum 0} macos? []
  (= "Mac OS X" (System/getProperty "os.name")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check! [canonical-path]
  (when (macos?)
    (let [paths (mapv str (take-while some? (iterate fs/parent canonical-path)))
          output (probe/read-output (into native-command paths) @native-script)]
      (when-not (= verified-output output)
        (boundary/refuse-acl! canonical-path))))
  canonical-path)

(comment ::posix-plus-macos-acls)
