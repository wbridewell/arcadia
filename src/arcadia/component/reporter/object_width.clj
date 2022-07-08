(ns arcadia.component.reporter.object-width
  "For the Mack and Rock inattentional blindness task, subjects are asked to
  compare the height and width of a cross. This component extracts (exact)
  width information from a proto-object representation, making this property
  almost immediately available.

  Focus Responsive
    * fixation

  Reports the perceived width of the proto-object at the location specified
  by the fixation request.

  Default Behavior
  None.

  Produces
   * object-width
       includes the :width of the proto-object and the :segment associated
       with the proto-object."
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.objects :as obj]))

;; NOTE: This component currently reports an object property in a way that
;; cannot be bound into an object file. This implementation resulted from
;; early attempts at extracting object properties, and should, in principle
;; be replaced with one that producers either a "gist" or an "object-property"
;; element.

(defn- make-width [segment width source]
  {:name "object-width"
   :arguments {:width width :segment segment}
   :world nil
   :type "instance"
   :source source})

(defrecord ObjectWidthReporter [buffer]
  Component
  (receive-focus
    [component focus content]
    (if-let [segment (and (= (:name focus) "fixation")
                          (= (:type focus) "instance")
                          (= (:world focus) nil) ;; direct perception only
                          (obj/get-segment focus content))]
      (reset! (:buffer component)
              (make-width segment (-> segment :region :width) component))
      (reset! (:buffer component) nil)))

  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method ObjectWidthReporter [comp ^java.io.Writer w]
  (.write w (format "ObjectWidthReporter{}")))

(defn start []
  (->ObjectWidthReporter (atom nil)))
