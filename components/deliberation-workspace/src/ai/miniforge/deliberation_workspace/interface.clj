;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-workspace.interface
  "Experimental reasoning state. Adoption never grants authority to an external effect.
   All functions are trusted-process APIs; expose only proposals to a model."
  (:require [ai.miniforge.deliberation-workspace.admission :as admission]
            [ai.miniforge.deliberation-workspace.commit :as commit]
            [ai.miniforge.deliberation-workspace.object :as object]
            [ai.miniforge.deliberation-workspace.projection :as projection]
            [ai.miniforge.deliberation-workspace.run :as run]))
(def new-object object/new-object)
(def admit admission/admit)
(def project projection/project)
(def activate run/activate-participant)
(def run run/run)
(def replay-transaction commit/commit)
