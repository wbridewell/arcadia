(ns arcadia.component.number-identifier
  "To preserve the distinction between numerals and numbers, this component
  transforms (single digit) characters associated with an object into their
  semantic form as numbers. This is part of the effort to move from
     visual feature -> character symbol -> semantic property.

  Focus Responsive
    * object

  Generates a semantic representation of a numeral associated with the object.

  Default Behavior
  None.

  Produces
   * object-property
       includes a :property argument that specifies the contents to be a
       :number, the integer :value of the number, and the :object to which the
       property belongs."
  (:require [arcadia.component.core :refer [Component]]))

(defn- parse-int
  "Turn a string representation of an integer into its integer value."
  [s]
  (when-let [i (re-find #"\A-?\d+" s)]
   (Integer/parseInt i)))

(defn- make-number [o v source]
  (when v
    {:name "object-property"
     :arguments {:property :number
                 :value v
                 :object o}
     :world nil
     :source source
     :type "instance"}))

(defrecord NumberIdentifier [buffer]
  Component
  (receive-focus
   [component focus content]
   (if (and (= (:type focus) "instance")
            (= (:name focus) "object")
            (-> focus :arguments :character))
     (reset! (:buffer component)
             (make-number focus
                          (parse-int (-> focus :arguments :character))
                          component))
     (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method NumberIdentifier [comp ^java.io.Writer w]
  (.write w (format "NumberIdentifier{}")))

(defn start []
  (->NumberIdentifier (atom nil)))
