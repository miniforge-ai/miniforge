;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-directory-policy
  "POSIX directory ownership and entry-replacement protection."
  (:require [ai.miniforge.workflow.messages :as messages]
            [babashka.fs :as fs])
  (:import [java.io IOException]
           [java.nio.file.attribute PosixFilePermission]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} private-permissions "rwx------")

(def ^{:stratum 0} ^:private root-owner "root")

(def ^{:stratum 0} ^:private sticky-bit 9)

(def ^{:stratum 0} ^:private other-writes
  #{PosixFilePermission/GROUP_WRITE PosixFilePermission/OTHERS_WRITE})

(defn ^{:stratum 0} refuse! [path]
  (throw (IOException. (messages/system :checkpoint/unsafe-directory {:path (str path)}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} trusted-owner? [owner]
  (contains? (hash-set root-owner (System/getProperty "user.name")) (str owner)))

(defn- ^{:stratum 1} writable-by-others? [attributes]
  (some other-writes (:permissions attributes)))

(defn- ^{:stratum 1} sticky? [path]
  (bit-test (:mode (fs/read-attributes path "unix:mode" {:nofollow-links true})) sticky-bit))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check! [path leaf?]
  (let [attributes (fs/read-attributes path "posix:*" {:nofollow-links true})
        protected? (or (not (writable-by-others? attributes))
                       (and (not leaf?) (sticky? path)))]
    (when-not (and (:isDirectory attributes) (trusted-owner? (:owner attributes)) protected?)
      (refuse! path))
    path))

(defn ^{:stratum 2} check-link! [path]
  (let [attributes (fs/read-attributes path "posix:*" {:nofollow-links true})]
    (when-not (trusted-owner? (:owner attributes)) (refuse! path))
    path))

(comment ::protected-checkpoint-directories)
