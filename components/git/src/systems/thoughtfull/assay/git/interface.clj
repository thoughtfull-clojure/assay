(ns systems.thoughtfull.assay.git.interface
  "Read revisions and changes from the Git repository containing a
  workspace."
  (:require
   [systems.thoughtfull.assay.git.core :as core]))

(defn merge-base
  "The commit where HEAD diverged from ref, as a full SHA."
  [root ref]
  (core/merge-base root ref))

(defn changed-files
  "Paths, relative to root, of files under root that differ between rev and
  the working tree, including untracked files that are not ignored."
  [root rev]
  (core/changed-files root rev))

(defn extract
  "Write the tree of rev under root (which may be a subdirectory of the
  repository) into the existing directory dir."
  [root rev dir]
  (core/extract root rev dir))
