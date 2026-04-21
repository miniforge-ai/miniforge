(ns ai.miniforge.pipeline-pack-store.schema
  "Datalevin schema for pipeline pack store.")

(def datalevin-schema
  {;; Pack manifests
   :pack/id            {:db/unique :db.unique/identity}
   :pack/name          {}
   :pack/version       {}
   :pack/description   {}
   :pack/author        {}
   :pack/trust-level   {}
   :pack/authority     {}

   ;; Metrics
   :metric/id          {:db/unique :db.unique/identity}
   :metric/name        {}
   :metric/family-id   {}
   :metric/source-type {}
   :metric/pack-id     {}

   ;; Metric value snapshots
   :snapshot/id         {:db/unique :db.unique/identity}
   :snapshot/metric-id  {}
   :snapshot/value      {}
   :snapshot/as-of      {}
   :snapshot/pipeline-run-id {}
   :snapshot/pack-id    {}

   ;; Pipeline runs
   :run/id             {:db/unique :db.unique/identity}
   :run/pack-id        {}
   :run/pipeline-name  {}
   :run/status         {}
   :run/started-at     {}
   :run/finished-at    {}
   :run/metric-count   {}})
