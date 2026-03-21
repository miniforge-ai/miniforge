(ns ai.miniforge.llm.core
  "LLM client implementation using CLI backends.

   Note: The protocol and implementation have been moved to:
   - ai.miniforge.llm.interface.protocols.llm-client (LLMClient protocol)
   - ai.miniforge.llm.protocols.impl.llm-client (implementation functions)
   - ai.miniforge.llm.protocols.records.llm-client (CLIClient record)

   This namespace remains for backward compatibility."
  (:require
   [ai.miniforge.llm.interface.protocols.llm-client :as p]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]
   [ai.miniforge.llm.protocols.records.llm-client :as records]))

;; Re-export protocol for backward compatibility
(def LLMClient p/LLMClient)

;; Re-export protocol methods
(def complete* p/complete*)
(def get-config p/get-config)

;; Re-export implementation functions
(def backends impl/backends)
(def build-messages-prompt impl/build-messages-prompt)
(def parse-cli-output impl/parse-cli-output)
(def default-exec-fn impl/default-exec-fn)
(def mock-exec-fn impl/mock-exec-fn)
(def mock-exec-fn-multi impl/mock-exec-fn-multi)

;; Re-export factory functions
(def create-client records/create-client)
