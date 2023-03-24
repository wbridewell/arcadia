(ns arcadia.component.recall-linker
  "If a listed item in WM has just been vocalized,
  trigger the recall of the next item in the list

  Focus Responsive
    * vocalize (for an :element in working memory)

  Default Behavior
  None.

  Produces
   * wm-recall
      including a :id argument specifying the id descriptor
      of the next element to be recalled from working memory."
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.general :as g]))

(defn- cue-recall [vocalize id prev]
  {:name "wm-recall"
   :arguments {:id id :prev prev :reason "cumulative"
               :subvocalize? (= (:name vocalize) "subvocalize")}
   :world nil
   :type "action"})

;; buffer: nil, or the recall action if present
(defrecord RecallLinker [buffer]
  Component
  (receive-focus
    [component focus content]
    (reset! (:buffer component) nil)

    (let [vocalize (g/find-first #(and (or (= (:name %) "vocalize")
                                           (= (:name %) "subvocalize"))
                                       (-> % :arguments :completed?))
                                 content)
          wm (filter #(= (:world %) "working-memory") content)
          element (when vocalize
                    (or (g/find-in (-> vocalize :arguments :element) wm)
                        (g/find-first #(-> % meta :id (= (:id (:arguments vocalize)))) wm)))
          next (some-> element meta :next :id)]
      (when (and vocalize element next)
        (reset! (:buffer component) (cue-recall vocalize next element)))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method RecallLinker [comp ^java.io.Writer w]
  (.write w (format "RecallLinker{}")))

(defn start []
  (->RecallLinker (atom nil)))
