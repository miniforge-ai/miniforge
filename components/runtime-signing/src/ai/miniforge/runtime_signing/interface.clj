;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.interface
  "Runtime-held signing key and HMAC-SHA256 signatures over a lossless
   canonical encoding.

   The runtime signs authority it issues (grants, the envelopes that allow
   an effect) and verifies those signatures before an effect commits. The
   key lives in an owner-only file under the miniforge home. A worker that
   can write the grant or effect stores but cannot read that file cannot
   forge authority; a process running as the same user can, so local-mode
   agents are not contained by this alone."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.runtime-signing.hmac :as hmac]
   [ai.miniforge.runtime-signing.key-file :as key-file]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} default-key-file
  "`<miniforge-home>/keys/runtime-signing.key`."
  []
  (io/file (config/miniforge-home) "keys" "runtime-signing.key"))

(def ^{:stratum 0} sign
  "Hex HMAC-SHA256 of a value's canonical encoding: (sign key-bytes value).
   Throws for a value that cannot be encoded losslessly."
  hmac/sign)

(def ^{:stratum 0} signed?
  "True only when a signature matches a value: (signed? key-bytes value sig).
   Never throws."
  hmac/signed?)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} load-or-create-key!
  "The runtime key bytes, creating an owner-only key file when absent, or
   an anomaly. Defaults to [[default-key-file]]."
  ([] (load-or-create-key! (default-key-file)))
  ([file] (key-file/load-or-create! (io/file file))))
