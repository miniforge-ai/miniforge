#!/usr/bin/env bb
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
;; Refresh `components/llm/resources/llm/cost-table.edn` from the
;; canonical upstream pricing source (BerriAI/litellm's
;; `model_prices_and_context_window.json`).
;;
;; Run locally:
;;
;;     bb scripts/refresh-cost-table.bb
;;
;; Run in CI:
;;
;;     `.github/workflows/refresh-cost-table.yml` invokes this on
;;     schedule (weekly) and on workflow_dispatch, then opens a PR
;;     if the EDN changed.
;;
;; The transform is keyed by the ids already in cost-table.edn —
;; this ETL refreshes *existing* prices, it does NOT add new models.
;; To track a new model, hand-edit cost-table.edn first (and
;; cost-source-mapping.edn if upstream's id differs from ours),
;; then the next refresh keeps it current.
;;
;; Exit code:
;;   0 — cost-table is current OR was refreshed successfully
;;   2 — upstream JSON couldn't be fetched / parsed
;;   3 — one or more priced models had no upstream match

(require '[babashka.curl :as curl]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

;; ----------------------------------------------------------------- Constants
;; Named here so the script doesn't carry magic strings/numbers and a
;; future audit can find the source URL + paths in one place.

(def cost-table-path
  "components/llm/resources/llm/cost-table.edn")

(def mapping-path
  "components/llm/resources/llm/cost-source-mapping.edn")

(def upstream-json-url
  "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json")

(def tokens-per-million
  "Same constant as ai.miniforge.llm.cost/tokens-per-million —
   denominator for the per-1M-token shape our cost-table uses.
   Duplicated here because bb scripts run outside the JVM classpath
   and re-requiring the clojure namespace would pull in the whole
   llm component for a single number."
  1000000.0)

(def exit-codes
  {:ok                        0
   :upstream-fetch-failed     2
   :upstream-coverage-missing 3})

;; ----------------------------------------------------------------- IO helpers

(defn- read-edn-file
  [path]
  (-> path slurp edn/read-string))

(defn- extract-preamble
  "Pull everything before the first `{` out of an EDN file's raw text.
   That's the comment header (license + documentation) the
   refresh-cost-table flow must preserve across rewrites — EDN
   parsing strips comments, so the structured data round-trip can't
   carry them forward on its own.

   Returns the preamble string (may include trailing whitespace
   before the `{`) or an empty string if the file has no
   pre-map content. Defensive: a missing file or one without `{`
   yields `\"\"` so the caller still produces valid EDN."
  [raw-text]
  (if-let [open-idx (str/index-of (or raw-text "") "{")]
    (subs raw-text 0 open-idx)
    ""))

(defn- write-edn-file
  "Write `data` as pretty-readable EDN to `path`, preserving the
   existing file's header comments (license + docs) so a refresh
   doesn't permanently lose the documentation. Each model-id gets
   its own line, with `:input-per-1m` and `:output-per-1m` columns
   aligned for visual diff. Sticks to the same shape
   `cost-table.edn` is hand-edited as, so a refresh PR reads as a
   per-cell update rather than a wholesale reformat."
  [path data]
  (let [models    (:pricing/by-model-id data)
        ordered   (->> models (sort-by key))
        key-width (->> ordered (map (comp count pr-str key))
                       (reduce max 0))
        rows (->> ordered
                  (map (fn [[k {:keys [input-per-1m output-per-1m]}]]
                         (format "  %s {:input-per-1m %6.2f :output-per-1m %6.2f}"
                                 (format (str "%-" key-width "s") (pr-str k))
                                 (double input-per-1m)
                                 (double output-per-1m)))))
        preamble (extract-preamble (when (fs/exists? path) (slurp path)))]
    (spit path
          (str preamble
               "{:pricing/by-model-id\n"
               " {\n"
               (->> rows (str/join "\n"))
               "}}\n"))))

;; ----------------------------------------------------------------- Fetch + transform

(defn fetch-upstream-json
  "GET the litellm JSON and parse it. Returns `{:ok true :body parsed}`
   or `{:ok false :error <message>}`. Anomaly-style return; no
   throws — caller branches on `:ok` to decide exit code."
  []
  (try
    (let [{:keys [status body]} (curl/get upstream-json-url
                                          {:throw false
                                           :connect-timeout 30
                                           :max-time 30})]
      (if (= 200 status)
        {:ok true :body (json/parse-string body true)}
        {:ok false :error (str "HTTP " status " from " upstream-json-url)}))
    (catch Exception e
      {:ok false :error (.getMessage e)})))

(defn upstream-pricing
  "Look up our `model-id` in the upstream JSON via `mapping`.
   Falls through to the direct upstream key if no mapping entry
   AND the upstream JSON has our id verbatim — handles the case
   where upstream and our naming line up.
   Returns `{:input-per-1m :output-per-1m}` or nil."
  [upstream-json mapping model-id]
  (let [upstream-key (get mapping model-id model-id)
        entry        (get upstream-json (keyword upstream-key))]
    (when entry
      (let [in  (:input_cost_per_token entry)
            out (:output_cost_per_token entry)]
        (when (and (number? in) (number? out))
          {:input-per-1m  (* in  tokens-per-million)
           :output-per-1m (* out tokens-per-million)})))))

(defn transform
  "Refresh each entry in `current` against `upstream` via `mapping`.
   Returns `{:refreshed <new-table> :unmatched [...] :changed
   [{:model :before :after}]}`. Unmatched models keep their existing
   prices — operator-curated entries don't get silently zeroed out
   when upstream renames or drops them."
  [current-table mapping upstream]
  (let [current (:pricing/by-model-id current-table)]
    (reduce
      (fn [acc [model-id current-price]]
        (if-let [fresh (upstream-pricing upstream mapping model-id)]
          (cond-> (update acc :refreshed assoc model-id fresh)
            (not= fresh current-price)
            (update :changed conj {:model model-id
                                   :before current-price
                                   :after  fresh}))
          (-> acc
              (update :refreshed assoc model-id current-price)
              (update :unmatched conj model-id))))
      {:refreshed {} :unmatched [] :changed []}
      current)))

;; ----------------------------------------------------------------- Report

(defn- format-price [{:keys [input-per-1m output-per-1m]}]
  (format "$%.2f in / $%.2f out per 1M" (double input-per-1m) (double output-per-1m)))

(defn print-summary
  "Human-readable change summary. Used as the GitHub Action's job
   output AND as the PR body when the script lands changes."
  [{:keys [refreshed unmatched changed]}]
  (println (format "Models in cost-table: %d" (count refreshed)))
  (println (format "Models updated:       %d" (count changed)))
  (println (format "Models unmatched:     %d" (count unmatched)))
  (when (seq changed)
    (println "\nChanges:")
    (doseq [{:keys [model before after]} changed]
      (println (format "  %s: %s -> %s"
                       model
                       (format-price before)
                       (format-price after)))))
  (when (seq unmatched)
    (println "\nUnmatched models (kept existing prices):")
    (doseq [m unmatched]
      (println (format "  %s" m)))))

;; ----------------------------------------------------------------- Main

(defn -main
  [& _args]
  (let [current  (read-edn-file cost-table-path)
        mapping  (if (fs/exists? mapping-path)
                   (-> mapping-path read-edn-file :mapping/by-model-id)
                   (do (println "WARN: mapping file not found, defaulting to identity mapping:"
                                mapping-path)
                       {}))
        fetch    (fetch-upstream-json)]
    (cond
      (not (:ok fetch))
      (do (println "ERROR: upstream fetch failed:" (:error fetch))
          (System/exit (:upstream-fetch-failed exit-codes)))

      :else
      (let [{:keys [refreshed unmatched changed] :as result}
            (transform current mapping (:body fetch))]
        (write-edn-file cost-table-path
                        {:pricing/by-model-id refreshed})
        (print-summary result)
        (System/exit
          (if (seq unmatched)
            (:upstream-coverage-missing exit-codes)
            (:ok exit-codes)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
