(ns ai.miniforge.agent.file-artifacts
  "Fallback artifact collection from files written in the working tree."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.set :as set]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Porcelain parsing and artifact shaping

(def ^:private artifact-manifest-name
  "Optional metadata manifest written by the agent."
  "miniforge-artifact.edn")

(defn- tracked-addition?
  "Return true when the porcelain status indicates a newly added tracked file."
  [status]
  (or (= \A (nth status 0))
      (= \A (nth status 1))))

(defn- deleted?
  "Return true when the porcelain status indicates deletion."
  [status]
  (or (= \D (nth status 0))
      (= \D (nth status 1))))

(defn- modified?
  "Return true when the porcelain status indicates a tracked file change."
  [status]
  (or (= \M (nth status 0))
      (= \M (nth status 1))
      (= \R (nth status 0))
      (= \R (nth status 1))
      (= \C (nth status 0))
      (= \C (nth status 1))
      (= \T (nth status 0))
      (= \T (nth status 1))
      (tracked-addition? status)))

(defn- porcelain-entry
  "Parse a single git status --porcelain=v1 line."
  [line]
  (let [status (subs line 0 2)
        raw-path (subs line 3)
        path (if (str/includes? raw-path " -> ")
               (second (str/split raw-path #" -> " 2))
               raw-path)]
    (cond
      (= status "??") {:bucket :untracked :path path}
      (deleted? status) {:bucket :deleted :path path}
      (tracked-addition? status) {:bucket :added :path path}
      (modified? status) {:bucket :modified :path path}
      :else nil)))

(defn- empty-snapshot
  "Return an empty working tree snapshot."
  []
  {:untracked #{}
   :modified #{}
   :deleted #{}
   :added #{}})

(defn- add-entry
  "Add a parsed porcelain entry into the snapshot."
  [snapshot {:keys [bucket path]}]
  (update snapshot bucket conj path))

(defn- manifest-path
  "Return the manifest file path for a working dir."
  [working-dir]
  (str (io/file working-dir artifact-manifest-name)))

(defn- read-manifest
  "Read optional artifact metadata from the working directory."
  [working-dir]
  (let [path (manifest-path working-dir)
        file (io/file path)]
    (when (.exists file)
      (-> (slurp file)
          edn/read-string
          (select-keys [:code/summary :code/tests-needed?])))))

(defn- file-entry
  "Build a synthetic artifact entry for a written file."
  [working-dir action path]
  {:path path
   :content (if (= :delete action) "" (slurp (io/file working-dir path)))
   :action action})

(defn- changed-paths
  "Compute created/modified/deleted paths attributable to the current session."
  [pre post]
  (let [dirty-before (apply set/union (map #(get pre % #{})
                                           [:untracked :modified :added]))
        created (set/union
                 (set/difference (get post :untracked #{}) (get pre :untracked #{}))
                 (set/difference (get post :added #{}) (get pre :added #{})))
        modified (-> (set/difference (get post :modified #{}) (get pre :modified #{}))
                     (set/difference dirty-before)
                     (set/difference created))
        deleted (-> (set/difference (get post :deleted #{}) (get pre :deleted #{}))
                    (set/difference dirty-before))]
    {:create (disj created artifact-manifest-name)
     :modify (disj modified artifact-manifest-name)
     :delete (disj deleted artifact-manifest-name)}))

(defn- synthetic-artifact
  "Build a synthetic code artifact from written file sets."
  [working-dir changed]
  (let [files (->> [[:create (:create changed)]
                    [:modify (:modify changed)]
                    [:delete (:delete changed)]]
                   (mapcat (fn [[action paths]]
                             (map #(file-entry working-dir action %) (sort paths))))
                   vec)]
    (when (seq files)
      (merge {:code/files files
              :code/summary (str (count files)
                                 " files collected from agent working directory (no MCP submit)")
              :code/language "clojure"
              :code/tests-needed? true}
             (read-manifest working-dir)))))

;------------------------------------------------------------------------------ Layer 1
;; Working tree snapshot and fallback artifact collection

(defn snapshot-working-dir
  "Capture the current git dirty state for a working directory.

   Returns sets of paths relative to working-dir. Paths already staged as new
   files are tracked in :added so they can still be treated as :create during
   fallback artifact synthesis."
  [working-dir]
  (let [{:keys [exit out err]} (shell/sh "git" "-C" working-dir
                                         "status" "--porcelain=v1"
                                         "--untracked-files=all"
                                         "--ignored=no" "--" ".")]
    (if (zero? exit)
      (reduce add-entry
              (empty-snapshot)
              (keep porcelain-entry (str/split-lines out)))
      (throw (ex-info "Failed to snapshot working directory"
                      {:working-dir working-dir
                       :exit exit
                       :stderr err})))))

(defn collect-written-files
  "Collect files written during the agent session into a synthetic code artifact.

   Uses the pre-session snapshot to exclude files that were already dirty before
   the session started."
  [pre-snapshot working-dir]
  (when pre-snapshot
    (->> (snapshot-working-dir working-dir)
         (changed-paths pre-snapshot)
         (synthetic-artifact working-dir))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (snapshot-working-dir (System/getProperty "user.dir"))

  (collect-written-files (snapshot-working-dir (System/getProperty "user.dir"))
                         (System/getProperty "user.dir"))

  :leave-this-here)
