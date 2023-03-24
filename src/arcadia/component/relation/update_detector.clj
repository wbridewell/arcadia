(ns arcadia.component.relation.update-detector
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]))

;; This component encodes events whenever an update to a relation value is made

(def ^:private update-descriptor
  (d/descriptor :name "memory-equality" :type "relation" :world "working-memory"))

(defn make-event [relation-update]
  {:name "event"
   :arguments {:event-name "relation-update"
               :objects nil
               :event-lifespan 0
               :old (-> relation-update :arguments :old)
               :new (-> relation-update :arguments :new)}
   :type "instance"
   :world nil})

(defrecord RelationUpdateDetector [buffer]
  Component
  (receive-focus
    [component focus content]
    (->> content
         (d/filter-matches update-descriptor)
         (map #(make-event %))
         (reset! (:buffer component))))
  (deliver-result
    [component]
    @buffer))

(defmethod print-method RelationUpdateDetector [comp ^java.io.Writer w]
  (.write w (format "RelationUpdateDetector{}")))

(defn start []
  (->RelationUpdateDetector (atom nil)))
