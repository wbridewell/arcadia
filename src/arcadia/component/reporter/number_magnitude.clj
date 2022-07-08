(ns arcadia.component.reporter.number-magnitude
  "A common task in cued task-switching studies is to state whether the
  magnitude of a number is low or high. This typically means less than 5 or
  greater than 5, with the number 5 never appearing. This component looks
  at the numerical value associated with an object and generates the
  appropriate response.

  Focus Responsive
    * object

  If numerical information is available, reports whether the number is less
  than or greater than the threshold (typically 5).

  Default Behavior
  None.

  Produces
   * object-property
       includes a :property argument that specifies the contents to be a
       :magnitude, the \"low\" or \"high\" :value of the number, and the :object
       to which the property belongs."
  (:require [arcadia.component.core :refer [Component]]))

;; Task specific, consider exposing at the instantiation level.
(def ^:private magnitude-threshold 5)

(defn- make-magnitude [o v source]
    {:name "object-property"
     :arguments {:property :magnitude
                 :value v
                 :object o}
     :world nil
     :source source
     :type "instance"})

(defrecord NumberMagnitudeReporter [buffer]
  Component
  (receive-focus
   [component focus content]

   (if (and (= (:type focus) "instance")
            (= (:name focus) "object")
            (-> focus :arguments :number))
     (reset! (:buffer component)
             (make-magnitude focus
                          (if (> (->  focus :arguments :number) magnitude-threshold) :high :low)
                          component))
     (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method NumberMagnitudeReporter [comp ^java.io.Writer w]
  (.write w (format "NumberMagnitudeReporter{}")))

(defn start []
  (->NumberMagnitudeReporter (atom nil)))
