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
(ns ai.miniforge.cli.observability.tailing
  "Generic file-tailing mechanics shared by 'mf logs tail' and 'mf events
   tail': last-N-lines display, forward-tailing a growing file, the
   stream header, and the list/cat command helpers. Split out of
   `ai.miniforge.cli.observability` (rule 210: the combined namespace
   measured 7 real layers, max 3) — same approach as the policy-pack
   loader split, miniforge#1772, and detection split, miniforge#1761/#1773.

   Layer 0: show-last-n-lines, tail-file, print-stream-header,
     list-files-command, cat-file-command — pure or leaf-level, no
     same-file dependents
   Layer 1: tail-stream-file (over Layer 0)"
  (:require
   [clojure.java.io :as io]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.observability.formatting :as formatting]))

;------------------------------------------------------------------------------ Layer 0

;; Tailing Helpers
(defn ^{:stratum 0} show-last-n-lines
  "Show last N lines from a file without following.

   Arguments:
     file-path - Path to file
     parse-fn - Function to parse each line
     format-fn - Function to format parsed entry
     lines - Number of lines to show
     filter-fn - Optional predicate to filter entries"
  [file-path parse-fn format-fn lines & [filter-fn]]
  (let [file (io/file file-path)
        filter-fn (or filter-fn (constantly true))]
    (with-open [rdr (io/reader file)]
      (doseq [line (take-last lines (line-seq rdr))]
        (when-let [entry (parse-fn line)]
          (when (filter-fn entry)
            (println (format-fn entry))))))))

;; File Tailing
(defn ^{:stratum 0} tail-file
  "Tail a file and print each new line.

   Arguments:
     file-path - String path to file
     format-fn - Function to format each line
     lines - Number of initial lines to show (default 10)

   Returns: Never (blocks forever)"
  [file-path format-fn & [{:keys [lines] :or {lines 10}}]]
  (let [file (io/file file-path)
        raf (java.io.RandomAccessFile. file "r")]
    (try
      ;; Show last N lines
      (let [file-length (.length file)
            start-pos (max 0 (- file-length (* lines 200)))] ; Rough estimate
        (.seek raf start-pos)
        (doseq [line (line-seq (io/reader raf))]
          (when-let [formatted (format-fn line)]
            (println formatted))))

      ;; Tail forever
      (loop [last-pos (.length file)]
        (Thread/sleep 500)
        (let [current-length (.length file)]
          (if (> current-length last-pos)
            (do
              (.seek raf last-pos)
              (doseq [line (line-seq (io/reader raf))]
                (when-let [formatted (format-fn line)]
                  (println formatted)))
              (recur (.length file)))
            (recur last-pos))))
      (catch Exception e
        (println (formatting/colorize :red (messages/t :observability/error-tailing {:error (.getMessage e)}))))
      (finally
        (.close raf)))))

(defn ^{:stratum 0} print-stream-header
  "Print header for stream tailing.

   Arguments:
     icon - Icon to show (e.g., 📋 or 📊)
     label - Stream label (e.g., 'logs' or 'events')
     file-path - Path being tailed
     extra-info - Optional extra info to display (e.g., filter)"
  [icon label file-path & [extra-info]]
  (println (formatting/colorize :cyan (messages/t :observability/tailing-header {:icon icon :label label :file-path file-path})))
  (when extra-info
    (println (formatting/colorize :gray extra-info)))
  (println (formatting/colorize :gray (apply str (repeat 80 "─")))))

;; Command Helpers
(defn ^{:stratum 0} list-files-command
  "List files with sizes.

   Arguments:
     find-fn - Function to find files
     label - Label for output (e.g., 'log files')"
  [find-fn label]
  (let [files (find-fn)]
    (if (seq files)
      (do
        (println (formatting/colorize :cyan (messages/t :observability/available-files {:label label})))
        (doseq [f files]
          (let [size-mb (/ (.length (io/file f)) 1024.0 1024.0)]
            (println (messages/t :observability/file-entry {:path f :size (format "%.2f" size-mb)})))))
      (println (formatting/colorize :yellow (messages/t :observability/no-files-found {:label label}))))))

(defn ^{:stratum 0} cat-file-command
  "Display contents of a file.

   Arguments:
     file - File path to display"
  [file]
  (if file
    (println (slurp file))
    (println (formatting/colorize :red (messages/t :observability/file-required)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} tail-stream-file
  "Generic file tailing for logs/events.

   Arguments:
     opts - Map with:
       :file-path - Path to file
       :parse-fn - Function to parse line
       :format-fn - Function to format entry
       :filter-fn - Optional predicate
       :lines - Lines to show initially
       :follow - Whether to tail -f
       :icon - Header icon
       :label - Header label
       :extra-info - Extra header info"
  [{:keys [file-path parse-fn format-fn filter-fn lines follow icon label extra-info]
    :or {lines 10 follow true filter-fn (constantly true)}}]
  (if (.exists (io/file file-path))
    (do
      (print-stream-header icon label file-path extra-info)
      (if follow
        (tail-file file-path
                   (fn [line]
                     (when-let [entry (parse-fn line)]
                       (when (filter-fn entry)
                         (format-fn entry))))
                   {:lines lines})
        (show-last-n-lines file-path parse-fn format-fn lines filter-fn)))
    (println (formatting/colorize :yellow (messages/t :observability/file-not-found {:file-path file-path})))))
