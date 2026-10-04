(ns systems.thoughtfull.assay.main
  "Command line entry point: `clojure -M:run path ...`"
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [systems.thoughtfull.assay :as assay]))

(defn- clojure-file?
  [^java.io.File f]
  (and (.isFile f)
    (re-find #"\.clj[csx]?$" (.getName f))))

(defn- source-files
  [paths]
  (->> paths
    (mapcat (comp file-seq io/file))
    (filter clojure-file?)
    (sort-by str)))

(defn -main
  [& paths]
  (doseq [f (source-files (or (seq paths) ["src"]))]
    (pprint/pprint {:file (str f) :metrics (assay/analyze-file f)})))
