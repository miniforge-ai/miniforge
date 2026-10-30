;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.interface
  "Runtime-held signing key and HMAC-SHA256 signatures over canonical EDN.

   The runtime signs authority it issues (grants, the envelopes that allow
   an effect) and verifies those signatures before an effect commits. The
   key lives in an owner-only file under the miniforge home. A worker that
   can write the grant or effect stores but cannot read that file cannot
   forge authority; a process running as the same user can, so local-mode
   agents are not contained by this alone."
  (:require
   [ai.miniforge.runtime-signing.hmac :as hmac]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} sign
  "Hex HMAC-SHA256 of a value's canonical EDN: (sign key-bytes value)."
  hmac/sign)

(def ^{:stratum 0} signed?
  "True only when a signature matches a value: (signed? key-bytes value sig).
   Never throws."
  hmac/signed?)
