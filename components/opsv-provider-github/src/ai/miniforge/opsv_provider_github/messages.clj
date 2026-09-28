;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.messages
  (:require [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} t (messages/create-translator "config/opsv-provider-github/messages/system.edn"
                                  :opsv-provider-github/system))

(comment
  (t :input/invalid))
