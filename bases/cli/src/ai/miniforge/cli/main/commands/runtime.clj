;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.cli.main.commands.runtime
  "`mf runtime` subcommands — info / run.

   Phase 3 of N11-delta. Exposes the runtime adapter to end users:
   - `mf runtime info` — print the resolved runtime descriptor as data.
   - `mf runtime run -- <args>` — pass-through to the resolved runtime CLI.

   The selection algorithm (explicit config first, then probe order
   `[:podman :docker :nerdctl]`, then unavailable) lives in
   `dag-executor.protocols.impl.runtime.selector`. This namespace owns the
   user-facing rendering."
  (:require
   [babashka.process :as process]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Layer 0
;; Config sourcing

(def ^:private runtime-env-var
  "MINIFORGE_RUNTIME environment variable — overrides file config per
   N11-delta §2.1."
  "MINIFORGE_RUNTIME")

(defn- env-runtime-kind
  "Read MINIFORGE_RUNTIME from the environment and coerce to a keyword.
   Returns nil when unset or blank."
  []
  (some-> (System/getenv runtime-env-var) str/trim not-empty keyword))

(defn- selection-config
  "Build the config map passed to the selector. Today the only source is
   the env var; file config integration is a separate concern that layers
   on without changing the selector contract."
  []
  (cond-> {}
    (env-runtime-kind) (assoc :runtime-kind (env-runtime-kind))))

;------------------------------------------------------------------------------ Layer 1
;; Render helpers

(defn- format-probed-kind
  "Render one entry from the auto-probe :probed list — '<kind> (<version>)'
   for available kinds, '<kind> — <reason>' for unavailable."
  [{:keys [kind available? runtime-version reason]}]
  (if available?
    (messages/t :runtime/probed-available
                {:kind            (name kind)
                 :runtime-version (or runtime-version "?")})
    (messages/t :runtime/probed-unavailable
                {:kind   (name kind)
                 :reason (or reason "")})))

(defn- format-probed-list
  [probed]
  (when (seq probed)
    (str/join ", " (map format-probed-kind probed))))

;------------------------------------------------------------------------------ Layer 2
;; `mf runtime info`

(defn- print-info-success
  "Render a successful selection as human-readable lines plus the raw
   descriptor as EDN at the end (so `mf runtime info` is grep-able and
   programmable)."
  [{:keys [descriptor kind selection runtime-version probed]}]
  (println (messages/t :runtime/info-kind             {:kind (name kind)}))
  (println (messages/t :runtime/info-executable       {:executable (dag/runtime-executable descriptor)}))
  (println (messages/t :runtime/info-runtime-version  {:runtime-version (or runtime-version "?")}))
  (println (messages/t :runtime/info-selection        {:selection (name selection)}))
  (when (seq probed)
    (println (messages/t :runtime/info-probed         {:probed (format-probed-list probed)})))
  (println)
  (pprint/pprint descriptor))

(defn- print-info-error
  [error]
  (display/print-error
   (messages/t :runtime/error-no-runtime
               {:code    (some-> (:code error) name)
                :message (:message error)})))

(defn runtime-info-cmd
  "Resolve the runtime per N11-delta §3 and print the result. Used both
   from `mf runtime info` and from the doctor."
  [_m]
  (let [result (dag/select-runtime (selection-config))]
    (if (dag/ok? result)
      (print-info-success (:data result))
      (print-info-error (:error result)))))

;------------------------------------------------------------------------------ Layer 3
;; `mf runtime run -- <args>`

(defn- forward-args
  "Pull the args list off the babashka.cli dispatch map and strip a
   leading `--` separator if present. Matches the convention used by
   `mf worktree run -- <cmd>`."
  [m]
  (->> (get m :args [])
       (remove #{"--"})
       vec))

(defn runtime-run-cmd
  "Resolve the runtime, then exec `<resolved-exe> <args>`. Args after
   `--` are forwarded verbatim. Exit code propagates.

   This pass-through is for ad-hoc use (`mf runtime run --rm hello-world`).
   The workflow engine builds its own argv via the OCI-CLI executor; it
   does NOT shell through this command."
  [m]
  (let [result (dag/select-runtime (selection-config))]
    (if (dag/err? result)
      (do (print-info-error (:error result))
          (System/exit 1))
      (let [{:keys [descriptor]} (:data result)
            exe                  (dag/runtime-executable descriptor)
            args                 (forward-args m)
            proc                 (process/process (into [exe] args)
                                                  {:inherit true})
            exit                 (-> proc deref :exit)]
        (when (and (number? exit) (not (zero? exit)))
          (System/exit exit))))))

;------------------------------------------------------------------------------ Layer 4
;; Doctor integration

(defn- format-runtime-line
  "One-line summary for the doctor. Uses display/style for color."
  [{:keys [kind selection runtime-version]}]
  (str (display/style "✓" :foreground :green) " "
       (messages/t :runtime/doctor-line
                   {:kind            (name kind)
                    :runtime-version (or runtime-version "?")
                    :selection       (name selection)})))

(defn- print-runtime-error-line
  [error]
  (println (display/style "✗" :foreground :red) " "
           (messages/t :runtime/doctor-error-line
                       {:code    (some-> (:code error) name)
                        :message (:message error)})))

(defn print-doctor-runtime-section
  "Emit the runtime block of `mf doctor`. Picked up by main.clj's
   doctor-cmd."
  []
  (let [result (dag/select-runtime (selection-config))]
    (if (dag/ok? result)
      (let [data (:data result)]
        (println (format-runtime-line data))
        (when-let [probed-str (format-probed-list (:probed data))]
          (println "  " (messages/t :runtime/doctor-probed-line
                                    {:probed probed-str})))
        (println "  " (messages/t :runtime/doctor-override-hint
                                  {:env-var runtime-env-var})))
      (do (print-runtime-error-line (:error result))
          (println "  " (messages/t :runtime/doctor-override-hint
                                    {:env-var runtime-env-var}))))))
