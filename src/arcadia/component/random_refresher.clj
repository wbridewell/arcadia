(ns arcadia.component.random-refresher
  "refresh random items in working memory to ensure that they don't disappear

  Focus Responsive
     None.

  Default Behavior
     Produces a refresh request for a random element in WM.

  Produces
   * refresh
      a cue to the attentional strategy with the :element to focus on"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.general :as g]))

(def ^:parameter retrieval-threshold 0.0)

(defn- refresh [element]
  {:name "refresh"
   :arguments {:element element
               :reason "random"}
   :world nil
   :type "action"})

(defrecord RandomRefresher [buffer retrieval-threshold]
  Component
  (receive-focus
    [component focus content]

   ;; refresh a random element in WM
    (if-let [rand (g/rand-if-any (filter #(and (= (:world %) "working-memory")
                                               (some-> % meta :activation (>= retrieval-threshold)))
                                         content))]
      (reset! (:buffer component) (refresh rand))
      (reset! (:buffer component) nil)))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method RandomRefresher [comp ^java.io.Writer w]
  (.write w (format "RandomRefresher{}")))

(defn start
  [& {:as args}]
  (->RandomRefresher (atom nil)
                     (:retrieval-threshold (merge-parameters args))))
