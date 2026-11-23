;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-submission-native-fixtures
  "Disposable executables accept kubectl-shaped argv but never call a provider."
  (:require [ai.miniforge.dag-executor.kubernetes-creation-fixtures :as replies]
            [babashka.fs :as fs]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} complete-script
  "Capture actual argv and complete stdin before emitting the exact fixture reply."
  "#!/bin/sh\nset -eu\nfixture_dir=${0%/*}\nprintf '%s\\n' \"$@\" > \"$fixture_dir/argv\"\ncat > \"$fixture_dir/stdin\"\ncat \"$fixture_dir/reply\"\n")

(def ^{:stratum 0} blocked-script
  "A non-reader creates real pipe backpressure until native cleanup kills it."
  "#!/bin/sh\nexec sleep 30\n")

(def ^{:stratum 0} early-reply-script
  "Emit a valid receipt without accepting stdin; zero exit is not delivery proof."
  "#!/bin/sh\ncat \"${0%/*}/reply\"\n")

(def ^{:stratum 0} oversized-value
  "Four MiB exceeds pipe capacity, making incomplete delivery observable."
  (apply str (repeat (* 4 1024 1024) "x")))

(defn ^{:stratum 0} with-executable! [job script f]
  (fs/with-temp-dir [directory {}]
    (let [executable (fs/path directory "fixture-cli")
          response (json/generate-string (replies/created-job job))]
      (spit (str executable) script)
      (fs/set-posix-file-permissions executable "rwx------")
      (spit (str (fs/path directory "reply")) response)
      (f directory (str executable)))))

(comment (count oversized-value))
