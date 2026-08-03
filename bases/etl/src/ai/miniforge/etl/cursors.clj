;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.etl.cursors
  "Cross-run watermarks for one pack execution.

   `pipeline-runner` threads a cursor map through a single run and
   reports the one that run produced; `cursor-store` persists a cursor
   map to disk. Neither knows about the other, and a run has no memory
   of the last one until something joins them. That is this namespace:
   load the previous run's watermark into the context the runner reads
   prior cursors from, write the new one back once the run has
   succeeded.

   It is its own namespace rather than a handful of helpers inside
   `etl.runner` because the two policy questions it answers — which
   execution modes carry a watermark, and which run outcomes may
   advance one — belong in one stated place, and folding them into the
   runner would push that file past its stratum budget.

   Strata: mode policy, key translation, and the failure constructors
   (0); the mode predicate over that policy (1); the pair of entry
   points `etl.runner` calls (2). Strata are per-file, so these numbers
   say nothing about `etl.runner`'s own."
  {:miniforge/runtime :jvm-only}
  (:require
   [ai.miniforge.cursor-store.interface :as cursor-store]
   [ai.miniforge.etl.messages :as msg]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private cursor-bearing-modes
  "Execution modes whose semantics depend on a cross-run watermark.

   Only `:incremental` both honours one and advances it.
   `:full-refresh` re-reads its whole source by definition, so applying
   a stored watermark would silently narrow it into something else.
   `:backfill` and `:reprocess` deliberately re-read history, so the
   watermark they end on sits behind the incremental one — persisting
   it would drag the resume point backwards and re-ingest everything
   between."
  #{:incremental})

(defn- ^{:stratum 0} run-schema-name
  "The schema name this run will extract `stage` under.

   Mirrors `pipeline-runner`'s own `resolve-schema-name`, which is
   private to `run.clj`: a connector takes a resource key (\"issues\",
   \"pulls\") where the stage config names one, and the stage name
   otherwise. Duplicated rather than shared because reaching it would
   mean widening pipeline-runner's interface — but the two must agree,
   because a cursor is only valid for the resource it was read from. If
   that fallback chain changes there, it changes here."
  [{:stage/keys [config name]}]
  (or (:github/resource config)
      (:gitlab/resource config)
      (:resource config)
      name))

(defn- ^{:stratum 0} load-failure
  "A cursor file that exists but cannot be read — malformed, or an I/O
   failure the store surfaces the same way. Nothing has executed yet,
   so there is no run to attach."
  [error]
  (schema/failure :pipeline-run (msg/t :run/cursor-load-failed {:error error})))

(defn- ^{:stratum 0} save-failure
  "A watermark the run reached but could not write. `run` is kept so
   the caller can still summarize and project what did execute."
  [run error]
  (schema/failure :pipeline-run
                  (msg/t :run/cursor-save-failed {:error error})
                  {:pipeline-run run}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} by-stage-id
  "Re-key a persisted cursor map onto this run's stage ids.

   `cursor-store` keys by [stage-name schema-name], the identity that
   survives between runs. `pipeline-runner` looks a stage's cursor up
   first by the `:stage/id` it was scheduled with, which is minted
   fresh every run. Translating here means durable identity stays in
   the store, run identity stays in the runner, and neither has to know
   the other's key space.

   An entry transfers only when its schema name matches the one this
   run will extract the stage under. Repointing a stage at a different
   resource leaves its old entry in the file under the old schema, and
   that watermark describes a different source — resuming from it would
   skip records that were never read. A stage with no matching entry
   starts fresh, which re-reads."
  [pipeline cursors]
  (reduce
   (fn [acc stage]
     (if-let [entry (get cursors [(:stage/name stage) (run-schema-name stage)])]
       (assoc acc (:stage/id stage) entry)
       acc))
   {}
   (:pipeline/stages pipeline)))

(defn- ^{:stratum 1} incremental?
  "Whether `pipeline` runs in a mode that carries a cross-run cursor.
   A pipeline with no `:pipeline/mode` does not; `pipeline/validate-pipeline`
   rejects it before execution either way."
  [pipeline]
  (contains? cursor-bearing-modes (:pipeline/mode pipeline)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prime-context
  "Merge the watermark left by the previous run of this pipeline into
   `context`, keyed onto this run's stage ids (see `by-stage-id`) under
   the key `pipeline-runner` reads prior cursors from. Returns
   `(schema/success :context …)`; a non-incremental pipeline gets its
   context back untouched.

   A cursor file that exists but cannot be read — malformed, or
   unreadable for any other reason the store reports — fails the run
   instead of starting fresh. An ingest stage with no cursor admits
   every record, so treating a read error as `no watermark` would
   quietly re-ingest the entire source — the exact outcome this store
   exists to prevent, and one that reports itself as a successful run.

   For an incremental pipeline the persisted file is the sole source of
   prior cursors: a `:pipeline-run/connector-cursors` entry already in
   `context` is replaced."
  [logger pipeline-path pipeline context]
  (if-not (incremental? pipeline)
    (schema/success :context context)
    (let [loaded (cursor-store/load-cursors logger pipeline-path)]
      (if (schema/failed? loaded)
        (load-failure (:error loaded))
        (schema/success :context
                        (assoc context
                               :pipeline-run/connector-cursors
                               (by-stage-id pipeline (:cursors loaded))))))))

(defn ^{:stratum 2} persist-cursors!
  "Write the watermark this run reached. Returns `result` unchanged
   when there is nothing to write, and a run failure when the write
   fails.

   Only a wholly successful incremental run advances the watermark. A
   run that ingested and then failed downstream is holding records that
   were extracted and never published; advancing past them would drop
   them for good, so a failed run leaves the previous watermark alone
   and the next run re-reads that window.

   A failed write does fail the run. The records did land, but an
   incremental pipeline that cannot record where it got to re-ingests
   its whole source on the next run, and every run after that —
   reporting success here would hide the regression until someone
   noticed the volume."
  [logger pipeline-path pipeline result]
  (if-not (and (incremental? pipeline) (schema/succeeded? result))
    result
    (let [run   (:pipeline-run result)
          saved (cursor-store/save-cursors
                 logger pipeline-path (:pipeline-run/connector-cursors run))]
      (if (schema/failed? saved)
        (save-failure run (:error saved))
        result))))
