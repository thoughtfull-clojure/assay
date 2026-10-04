(ns systems.thoughtfull.assay.workspace.interface
  "Discover the bricks of a Polylith workspace."
  (:require
   [systems.thoughtfull.assay.workspace.core :as core]))

(defn bricks
  "The bricks (components and bases) of the Polylith workspace at root, sorted
  by type then name. Each is a map of :name, :type (:component or :base),
  :dir (relative to root), and :files, the brick's Clojure source files
  under src, relative to root."
  [root]
  (core/bricks root))
