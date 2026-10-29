;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.messages
  (:require [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} t
  (messages/create-translator "config/observer/messages/en-US.edn" :observer/messages))

(comment ::observer-message-catalog)
