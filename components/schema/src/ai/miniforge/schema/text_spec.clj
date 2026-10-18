;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.schema.text-spec
  "Shared current-write text constraints; never normalize payload values.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NonBlankString
  [:and :string [:re #"(?sU).*\S.*"]])

(comment
  NonBlankString)
