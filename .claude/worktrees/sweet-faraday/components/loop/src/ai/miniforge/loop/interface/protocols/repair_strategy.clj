(ns ai.miniforge.loop.interface.protocols.repair-strategy
  "Public protocol for artifact repair strategies.

   This is an extensibility point - users can implement custom repair
   strategies by implementing this protocol.")

(defprotocol RepairStrategy
  "Protocol for artifact repair strategies.
   Strategies attempt to fix validation errors in artifacts."
  (can-repair? [this errors context]
    "Check if this strategy can handle the given errors.
     Returns true if the strategy should be attempted.")
  (repair [this artifact errors context]
    "Attempt to repair the artifact given the errors.
     Returns:
     {:success? boolean
      :artifact artifact-map (if success)
      :errors [error...] (if failure)
      :strategy keyword
      :tokens-used int (optional)
      :duration-ms int (optional)}
     The context map provides access to agent, logger, and config."))
