;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; vocalizer -- component that enables text-to-speech (tts) based on
;; vocalize environmental-actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ns arcadia.component.vocalizer
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.tts :as tts]
            [arcadia.utility.descriptors :as d]))

(defrecord Vocalizer [buffer]
  Component
  (receive-focus
   [component focus content]
   ;; assumes all changes are task relevant
   (let [vocalize-request (d/first-element content :name "vocalize" :type "environmental-action")]
       (when vocalize-request (tts/speak (-> vocalize-request :arguments :lexeme)))
       (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method Vocalizer [comp ^java.io.Writer w]
  (.write w (format "Vocalizer{}")))

(defn start []
  (Vocalizer. (atom nil)))
