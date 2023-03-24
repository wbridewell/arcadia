(ns arcadia.component.recall-initializer
  "Upon presentation of the cue stimulus, recall the first listed
  item in working memory (the item without a :prev descriptor).

  Focus Responsive
    * object-property (for a :character with a :value equal to cue-symbol)


  Default Behavior
  None.

  Produces
   * wm-recall
      includes a :id argument equal to the :id of the first listed item in WM"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.general :as g]))

(def ^:parameter ^:required cue-symbol "cue to match (required)" nil)

(defn- cue-recall [id]
  {:name "wm-recall"
   :arguments {:id id}
   :world nil
   :type "action"})

;; buffer: nil, or the recall command if present
;; cue-symbol: if this symbol is present, fire off recall from WM
(defrecord RecallInitializer [buffer cue-symbol]
  Component
  (receive-focus
    [component focus content]
    (reset! (:buffer component) nil)

    (when (and (= (:type focus) "instance")
               (= (:name focus) "object-property")
               (= (:world focus) nil)
               (= (-> focus :arguments :property) :character)
               (= (-> focus :arguments :value) cue-symbol))
      (when-let [id (->> content
                         (g/find-first #(and (= (:world %) "working-memory")
                                             (-> % meta :id)
                                             (-> % meta :prev nil?)))
                         meta :id)]
        (reset! (:buffer component) (cue-recall id)))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method RecallInitializer [comp ^java.io.Writer w]
  (.write w (format "RecallInitializer{}")))

(defn start [& {:as args}]
  (->RecallInitializer (atom nil) (:cue-symbol (merge-parameters args))))
