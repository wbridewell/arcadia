;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; object-count-subvocalizer -- component that produces vocalization elements
;; and assists with simulation of subvocalization. TODO: refactor to generalized
;; subvocalization component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ns arcadia.component.object-count-subvocalizer
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component]]))

(defn subvocalize-count [value duration cyclecount]
  {:name "vocalize"
   :arguments {:lexeme value :duration duration :cycle cyclecount}
   :type "action"
   :world nil})

(defn update-cycle-count [vocalize-il]
  (let [lexeme (:lexeme (:arguments vocalize-il))
        duration (:duration (:arguments vocalize-il))
        currentcycle (:cycle (:arguments vocalize-il))]
    (if (<= currentcycle duration)
      {:name "vocalize"
       :arguments {:lexeme lexeme :duration duration :cycle (inc currentcycle)}
       :type "action"
       :world nil}
      nil)))

;; this function based on values from ACT/R model in Huss and Byrne (ICCM 2003)
;; assumes 25ms per ARCADIA cycle
(defn calculate-duration [lexeme]
  (* 6 (/ (count lexeme) 3)))


(defrecord ObjectCountSubvocalizer [buffer]
  Component
  (receive-focus
    [component focus content]
   ;; assumes all changes are task relevant
    (let [number (filter #(and (= (:name %) "number")
                               (= (:world %) "working-memory")
                               (= (:type %) "instance")
                               (= (:usage (:arguments %)) "counter")
                               (= (:update-on (:arguments %)) "change"))
                         content)
          number-comparison (first (filter #(= (:name %) "number-comparison") content))
          phon-buffer (filter #(and (= (:name %) "lexeme")
                                    (= (:world %) "phonological-buffer")) content)
          lex-number (:lexeme (:arguments (first (filter #(= (:name %) "lexicalized-number") content))))]
      (cond

        (= (:name focus) "vocalize")
        (reset! (:buffer component) (update-cycle-count focus))

        (not (nil? lex-number))
        (reset! (:buffer component) (subvocalize-count lex-number (calculate-duration lex-number)
                                                       0))

        number-comparison
        (reset! (:buffer component) (subvocalize-count (:value (:arguments number-comparison))
                                                       (calculate-duration (:value (:arguments number-comparison)))
                                                       0))

        :else
        (reset! (:buffer component) nil))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method ObjectCountSubvocalizer [comp ^java.io.Writer w]
  (.write w (format "ObjectCountSubvocalizer{}")))

(defn start []
  (ObjectCountSubvocalizer. (atom nil)))
