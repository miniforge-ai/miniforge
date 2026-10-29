;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-directory-support
  (:require [ai.miniforge.workflow.checkpoint-temporary-support :as support]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [is]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} private-directory "rwx------")

(def ^{:stratum 0} shared-directory "rwxr-xr-x")

(def ^{:stratum 0} unsafe-directories ["rwxrwx---" "rwxrwxrwx"])

(defn ^{:stratum 0} read-value [path]
  (edn/read-string (slurp (fs/file path))))

(defn ^{:stratum 0} switch-alias! [write! alias replacement path content]
  (write! path content)
  (fs/delete alias)
  (fs/create-sym-link alias replacement))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} verify-unsafe-directory [root mode]
  (let [directory (fs/path root "shared")
        target (fs/path directory "snapshot.edn")]
    (support/write! target :original)
    (fs/set-posix-file-permissions directory mode)
    (is (instance? IOException (support/attempt-write! target :replacement)))
    (is (= mode (support/permission-mode directory)))
    (is (= :original (read-value target)))
    (is (= ["snapshot.edn"] (mapv fs/file-name (fs/list-dir directory))))
    (fs/set-posix-file-permissions directory private-directory)))

(comment ::checkpoint-directory-fixtures)
