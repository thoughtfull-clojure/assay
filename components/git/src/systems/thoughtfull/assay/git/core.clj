(ns systems.thoughtfull.assay.git.core
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- git-process
  ^ProcessBuilder [root & args]
  (ProcessBuilder. ^java.util.List (into ["git" "-C" (str root)] args)))

(defn- check
  [^Process p command]
  (let [err (slurp (.getErrorStream p))]
    (when-not (zero? (.waitFor p))
      (throw (ex-info (str command " failed: " (str/trim err))
               {:command command :exit (.exitValue p)})))))

(defn- git
  [root & args]
  (let [p (.start ^ProcessBuilder (apply git-process root args))
        out (slurp (.getInputStream p))]
    (check p (str "git " (str/join " " args)))
    out))

(defn merge-base
  [root ref]
  (str/trim (git root "merge-base" ref "HEAD")))

(defn changed-files
  [root rev]
  (into (sorted-set)
    (remove str/blank?)
    (concat
      (str/split-lines (git root "diff" "--name-only" "--no-renames" "--relative" rev))
      (str/split-lines (git root "ls-files" "--others" "--exclude-standard")))))

(defn extract
  [root rev dir]
  ;; Run from a subdirectory, git archive includes only that subdirectory,
  ;; with paths relative to it.
  (let [archive (.start (git-process root "archive" "--format=tar" rev))
        tar (.start (ProcessBuilder. ["tar" "-x" "-C" (str dir)]))]
    (with-open [in (.getInputStream archive)
                out (.getOutputStream tar)]
      (io/copy in out))
    (check archive (str "git archive " rev))
    (check tar "tar -x")
    dir))
