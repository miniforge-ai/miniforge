;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.stream-state-spec
  "Runtime stream shapes; structural admission does not confer execution authority."
  (:require [ai.miniforge.event-stream.publication-state :as publication]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Stream
  [:map [:events vector?] [:subscribers [:map-of :any ifn?]] [:filters [:map-of :any ifn?]]
   [:sequence-numbers [:map-of :any nat-int?]] [:sinks [:vector ifn?]] [:quiesced-workflows set?] [:in-flight nat-int?]])

(def ^{:stratum 0} Publication
  [:map [:store [:map [:commit! fn?] [:close! fn?]]]
   [:pending [:fn publication/queue?]] [:seen set?] [:draining? :boolean] [:closed? :boolean]
   [:directory schema/NonBlankString]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Current [:and Stream [:map [:publication Publication]]])

(def ^{:stratum 1} Legacy [:and Stream [:map [:publication {:optional true} [:not :any]]]])

(comment
  Current)
