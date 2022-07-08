(ns arcadia.component.phonological-buffer
  "This component implements ARCADIA's phonological buffer

  Focus Responsive
    * vocalize
    * number-report

  Default Behavior
  When focus on a vocalize element is detected, then
    the vocalized lexeme is stored/retained in the buffer.
  When a number-report is focused on, then the buffer is
    reset.

  Produces
  lexeme - corresponds to the word currently stored in the buffer

  Displays
  n/a"
  (:require [arcadia.component.core :refer [Component]]))

;; NOTE: For now, buffer only holds one element -- TODO: improve this
(defn retain-lexical-item [lexeme source]
  {:name "lexeme"
   :arguments {:lexeme lexeme}
   :world "phonological-buffer"
   :source source
   :type "instance"})

(defrecord PhonologicalBuffer [buffer]
  Component
  (receive-focus
   [component focus content]
   (cond (and (= (:name focus) "vocalize")
              (= (:type focus) "action"))

         (reset! (:buffer component) [(retain-lexical-item (:lexeme (:arguments focus)) component)])

         (and (= (:name focus) "memorize") (= (:name (:element (:arguments focus))) "number-report"))
         (reset! (:buffer component) [])))

  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method PhonologicalBuffer [comp ^java.io.Writer w]
  (.write w (format "PhonologicalBuffer{}")))

(defn start []
  (PhonologicalBuffer. (atom nil)))
