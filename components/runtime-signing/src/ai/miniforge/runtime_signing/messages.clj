;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.messages
  "Developer-facing messages for the runtime-signing component."
  (:require
   [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} t
  "Translate a runtime-signing system message."
  (messages/create-translator
   "config/runtime-signing/messages/system.edn"
   :runtime-signing/messages))
