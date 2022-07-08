(ns arcadia.component.new-object-recorder
  "The appearance of a new object is not automatically remembered. Instead,
  this component requests that the object be stored in working memory for
  later reference. This retention enables the comparison of an object with
  other items even after the object has been pushed out of visual short-term
  memory.

  According to current thought, the representation of the object in working
  memory should be coarser grained than its representation in visual short-term
  memory, but this has not yet been implemented.

  Focus Responsive
    * visual-new-object, name & event, type

  Creates an action request to remember that a new object was seen.

  Default Behavior
  None.

  Produces
   * memorize
       includes the new visual object event as an :element."
  (:require  [arcadia.utility.descriptors :as d]
             [arcadia.component.core :refer [Component]]))

;; NOTE: This behavior should probably be moved to episodic memory one there is
;; a suitable implementation.
;;
;; NOTE: it may make sense to have a sort of "event short term memory"
;; that enables events to persist for a few cycles and be consolidated
;; into a more abstract event description. development of that ability
;; will require further research into episodic memory.

(defn record-new-object [element source]
  {:name "memorize"
   :arguments {:element element}
   :type "action"
   :world nil
   :source source})

(defrecord NewObjectRecorder [buffer]
  Component
  (receive-focus
   [component focus content]
   (if (d/element-matches? focus :name "visual-new-object" :type "event" :world nil?)
     (reset! (:buffer component) (record-new-object focus component))
     (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method NewObjectRecorder [comp ^java.io.Writer w]
  (.write w (format "NewObjectRecorder{}")))

(defn start []
  (->NewObjectRecorder (atom nil)))
