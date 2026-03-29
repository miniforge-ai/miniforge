(ns ai.miniforge.llm.protocols.records.llm-client
  "Record that implements LLMClient protocol.

   CLIClient - LLM client using CLI backends (claude, cursor, etc.)"
  (:require
   [ai.miniforge.llm.interface.protocols.llm-client :as p]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]))

(defrecord CLIClient [config logger exec-fn]
  p/LLMClient
  (complete* [this request]
    (impl/complete-impl this request))

  (complete-stream* [this request on-chunk]
    (impl/complete-stream-impl this request on-chunk))

  (get-config [this]
    (impl/get-config-impl this)))

;------------------------------------------------------------------------------ Layer 1
;; Factory functions

(defn create-client
  "Create a new LLM client using a CLI backend.

   Options:
   - :backend - Backend keyword (:codex, :claude, :cursor, :echo) - default :codex
   - :logger  - Optional logger for request/response logging
   - :exec-fn - Optional execution function override (for testing)

   Example:
     (create-client)  ; uses codex CLI
     (create-client {:backend :cursor})
     (create-client {:backend :claude :logger my-logger})"
  ([] (create-client {}))
  ([{:keys [backend logger exec-fn] :or {backend :codex}}]
   (when-not (contains? impl/backends backend)
     (throw (ex-info (str "Unknown backend: " backend)
                     {:backend backend
                      :available (keys impl/backends)})))
   (->CLIClient {:backend backend}
                logger
                (or exec-fn impl/default-exec-fn))))
