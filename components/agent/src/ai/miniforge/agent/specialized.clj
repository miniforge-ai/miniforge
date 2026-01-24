(ns ai.miniforge.agent.specialized
  "Specialized agent support for functional-style agents.

   Note: The protocol and implementation have been moved to:
   - ai.miniforge.agent.protocols.specialized (SpecializedAgent protocol - internal)
   - ai.miniforge.agent.protocols.impl.specialized (implementation functions)
   - ai.miniforge.agent.protocols.records.specialized (FunctionalAgent record)

   This namespace remains for backward compatibility."
  (:require
   [ai.miniforge.agent.protocols.specialized :as proto]
   [ai.miniforge.agent.protocols.records.specialized :as records]))

;; Re-export protocol for backward compatibility (internal protocol)
(def SpecializedAgent proto/SpecializedAgent)

;; Re-export protocol methods
(def invoke proto/invoke)
(def validate proto/validate)
(def repair proto/repair)

;; Re-export factory functions (not record itself - it's a Java class)
(def create-base-agent records/create-base-agent)
(def make-validator records/make-validator)
(def cycle-agent records/cycle-agent)

;; For code that needs the record type, import it from records namespace
;; e.g., (import ai.miniforge.agent.protocols.records.specialized.FunctionalAgent)
