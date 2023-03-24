(ns arcadia.component.length-comparator
  "This component compares the horizontal and vertical dimensions of an
  object and reports whether the object is taller than it is wider or vice
  versa. Carrying out the comparison requires focusing on the associated
  object, but the width and height information are carried out pre-attentively.

  Focus Responsive
    * object

  When both an object-width and object-height are available, compare them and
  report which one is longer.

  Default Behavior
  None.

  Produces
   * comparison
       includes the :properties that are being compared, the name of the
       :longer-dimension, and the :object driving the comparison."
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility [general :as g]]))

;; NOTE: only one height and width will appear at a time because they
;; result from focusing on a fixation.

(defn- compare-dimensions
  "Give an object-width and an object-height element, report the name of the
  longer dimension or if they are equal."
  [w h]
  (let [height (:height (:arguments h))
        width (:width (:arguments w))]
    (cond (> width height) "width"
          (> height width) "height"
          :else "equal")))

(defn- make-comparison [object longer-dimension]
  (println "make-comparison longer-dim = " (str longer-dimension))
  {:name "comparison"
   :arguments {:properties ["width" "height"]
               :longer longer-dimension
               :object object}
   :world nil
   :type "instance"})

(defrecord LengthComparator [buffer]
  Component
  (receive-focus
    [component focus content]
    (reset! (:buffer component) nil)
    (let [width (g/find-first #(= (:name %) "object-width") content)
          height (g/find-first #(= (:name %) "object-height") content)]
      (when (and width height (= (:name focus) "object"))
        (reset! (:buffer component) (make-comparison focus (compare-dimensions width height))))))
  
  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method LengthComparator [comp ^java.io.Writer w]
  (.write w (format "LengthComparator{}")))

(defn start []
  (->LengthComparator (atom nil)))
