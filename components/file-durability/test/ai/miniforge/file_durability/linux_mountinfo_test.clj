;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.linux-mountinfo-test
  (:require [ai.miniforge.file-durability.boundary.linux-mountinfo :as mountinfo]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} root
  "An eligible ancestor must not mask an unsupported, more specific mount."
  "36 35 98:0 / / rw - ext4 /dev/root rw")

(defn- ^{:stratum 0} nested [type mount-options super-options]
  (str "37 36 98:1 / /mnt/local\\040disk " mount-options
       " shared:1 - " type " /dev/sample " super-options))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} observe [path & mounts]
  (mountinfo/matching-mount (fs/path path) (str/join "\n" (cons root mounts))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-specific-supported-mount-wins-over-an-eligible-ancestor
  (let [path "/mnt/local disk/run"
        mount (observe path (nested "xfs" "rw" "rw"))]
    (is (= "37" (:mount/id mount)))
    (is (= "98:1" (:mount/device mount)))
    (is (= "xfs" (:mount/type mount)))
    (is (= "36" (:mount/id (observe "/mnt/local disk-unrelated/run" (nested "tmpfs" "rw" "rw")))))))

(deftest ^{:stratum 2} test-unsupported-most-specific-mount-does-not-fall-back
  (doseq [type ["tmpfs" "nfs4" "overlay" "fuse.example" "unknown"]]
    (is (nil? (observe "/mnt/local disk/run" (nested type "rw" "rw"))))))

(deftest ^{:stratum 2} test-read-only-filesystems-and-known-unsafe-barrier-options-are-refused
  (doseq [[mount-options super-options] [["ro" "rw"] ["rw" "ro"] ["rw" "rw,barrier=0"] ["rw" "rw,norecovery"]]]
    (is (nil? (observe "/mnt/local disk/run" (nested "ext4" mount-options super-options))))))

(deftest ^{:stratum 2} test-ambiguous-or-unparseable-mount-tables-are-refused
  (let [path "/mnt/local disk/run"
        local (nested "xfs" "rw" "rw")]
    (is (nil? (observe path local (str/replace-first local #"^37" "38"))))
    (is (nil? (observe path local "unparseable mount")))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.linux-mountinfo-test))
