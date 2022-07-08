(ns arcadia.component.java-memory-manager
  "Ensures that we don't run out of memory by calling System/gc periodically."
  (:require [arcadia.component.core :refer [Component merge-parameters]]))

(def ^:parameter gc-frequency "Do a manually garbage collection call after
  this many cycles." 60)

(defrecord JavaMemoryManager [counter gc-frequency]
  Component
  (receive-focus
   [component focus content]
   (when (zero? (mod (swap! (:counter component) inc) gc-frequency))
     (println "Manual garbage collect...")
     (doall (System/gc))))

  (deliver-result
   [component]))

(defmethod print-method JavaMemoryManager [comp ^java.io.Writer w]
  (.write w (format "JavaMemoryManager{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->JavaMemoryManager (atom 0) (:gc-frequency p))))
