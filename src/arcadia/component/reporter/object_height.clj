(ns arcadia.component.reporter.object-height
  "For the Mack and Rock inattentional blindness task, subjects are asked to
  compare the height and width of a cross. This component extracts (exact)
  height information from a proto-object representation, making this property
  almost immediately available.

  Focus Responsive
    * fixation

  Reports the perceived height of the proto-object at the location specified
  by the fixation request.

  Default Behavior
  None.

  Produces
   * object-height
       includes the :height of the proto-object and the :segment associated
       with the proto-object."
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.objects :as obj]))

;; NOTE: This component currently reports an object property in a way that
;; cannot be bound into an object file. This implementation resulted from
;; early attempts at extracting object properties, and should, in principle
;; be replaced with one that producers either a "gist" or an "object-property"
;; element.

(defn- make-height [segment height source]
  {:name "object-height"
   :arguments {:height height  :segment segment}
   :world nil
   :type "instance"
   :source source})

(defrecord ObjectHeightReporter [buffer]
  Component
  (receive-focus
    [component focus content]
    (if-let [segment (and (= (:name focus) "fixation")
                          (= (:type focus) "instance")
                          (= (:world focus) nil) ;; direct perception only
                          (obj/get-segment focus content))]
      (reset! (:buffer component)
              (make-height segment (-> segment :region :height) component))
      (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method ObjectHeightReporter [comp ^java.io.Writer w]
  (.write w (format "ObjectHeightReporter{}")))

(defn start []
  (->ObjectHeightReporter (atom nil)))
