(ns ai.miniforge.loop.interface.protocols.gate
  "Public protocol for validation gates.

   This is an extensibility point - users can implement custom gates
   by implementing this protocol.")

(defprotocol Gate
  "Protocol for validation gates.
   Gates check artifacts and return pass/fail results with errors."
  (check [this artifact context]
    "Run gate check on artifact. Returns:
     {:gate/id keyword
      :gate/type keyword
      :gate/passed? boolean
      :gate/errors [{:code keyword :message string :location map}...]
      :gate/warnings [...]}
     The context map provides access to logger, config, and other runtime state.")
  (gate-id [this]
    "Return the unique identifier for this gate.")
  (gate-type [this]
    "Return the gate type: :syntax, :lint, :test, :policy, :custom")
  (repair [this artifact violations context]
    "Attempt to repair artifact to fix violations.
     Returns:
     {:repaired? boolean
      :artifact artifact         ; Repaired artifact (if repaired? true)
      :changes [...]             ; List of changes made
      :remaining-violations [...]} ; Violations that couldn't be fixed
     If gate cannot perform repairs, return {:repaired? false}"))
