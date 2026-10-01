;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.spec)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} File [:fn #(instance? java.io.File %)])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} TextWrite [:tuple File :string])

(def ^{:stratum 1} ByteWrite [:tuple File bytes?])
