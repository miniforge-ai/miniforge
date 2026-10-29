;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-history-policy-test
  (:require [ai.miniforge.workflow.boundary.checkpoint-history :as history]
            [ai.miniforge.workflow.boundary.checkpoint-permissions :as permissions]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private first-record ":first\n")

(def ^{:stratum 0} ^:private second-record ":second\n")

(def ^{:stratum 0} ^:private shared-permissions "rw-r-----")

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-private-history [root]
  (let [path (fs/path root "history.edn")]
    (history/append! path first-record)
    (is (= permissions/private-permissions (fs/posix->str (fs/posix-file-permissions path))))
    (fs/set-posix-file-permissions path shared-permissions)
    (history/append! path second-record)
    (is (= shared-permissions (fs/posix->str (fs/posix-file-permissions path))))
    (is (= (str first-record second-record) (slurp (fs/file path))))))

(defn- ^{:stratum 1} verify-no-follow [root]
  (let [target (fs/path root "target.edn")
        alias (fs/path root "history.edn")]
    (spit (fs/file target) first-record)
    (fs/create-sym-link alias target)
    (is (thrown? IOException (history/append! alias second-record)))
    (is (= first-record (slurp (fs/file target))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} new-history-is-private-and-existing-sharing-is-preserved (fixture/call-with-posix-checkpoint-root verify-private-history))

(deftest ^{:stratum 2} history-append-does-not-follow-a-symlink (fixture/call-with-posix-checkpoint-root verify-no-follow))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.checkpoint-history-policy-test))
