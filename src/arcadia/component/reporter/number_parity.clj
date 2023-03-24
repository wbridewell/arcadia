(ns arcadia.component.reporter.number-parity
  "A common task in cued task-switching studies is to state whether the
  parity of a number is even or odd. This component looks at the numerical
  value associated with an object and generates the appropriate response.

  Focus Responsive
    * object

  If numerical information is available, reports whether the number is even
  or odd.

  Default Behavior
  None.

  Produces
   * object-property
       includes a :property argument that specifies the contents to be a
       :parity, the \"even\" or \"odd\" :value of the number, and the :object
       to which the property belongs."
  (:require [arcadia.component.core :refer [Component]]))

(defn- make-parity [o v]
    {:name "object-property"
     :arguments {:property :parity
                 :value v
                 :object o}
     :world nil
     :type "instance"})

(defrecord NumberParityReporter [buffer]
  Component
  (receive-focus
   [component focus content]
   (if (and (= (:type focus) "instance")
            (= (:name focus) "object")
            (-> focus :arguments :number))
     (reset! (:buffer component)
             (make-parity focus
                          (if (odd? (->  focus :arguments :number)) :odd :even)))
     (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   (list @buffer)))

(defmethod print-method NumberParityReporter [comp ^java.io.Writer w]
  (.write w (format "NumberParityReporter{}")))

(defn start []
  (->NumberParityReporter (atom nil)))
