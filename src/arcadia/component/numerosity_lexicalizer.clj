;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; numerosity-lexicalizer -- component that contains lexical representations
;; of number in sequence. responds to number-reports to subvocalize final
;; numerosity judgments, or responds to object fixations, to increment an
;; ongoing subvocalized count.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(ns arcadia.component.numerosity-lexicalizer
  "This component implements mapping from non-lexical numerosity
  representation from the OTS. Also contains number word sequencing
  for counting purposes.

  Focus Responsive
    * vstm-enumeration
    * object

  Default Behavior
  Responds to focus on vstm-enumeration and generates a number element
  Responds to focus on objects to prime next number in a counting rountine.

  Produces
  number - contains an exact numerosity.

  Displays
  n/a"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.vision.regions :as reg]
            [arcadia.utility [objects :as obj]]))


(def number-words ["zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen","fourteen",
                   "fifteen","sixteen","seventeen","eightteen","nineteen","twenty"])

(defn lexicalize-numerosity [number source]
  {:name "lexicalized-number"
   :arguments {:lexeme (nth number-words number) :number number}
   :type "instance"
   :world nil
   :source source})

(def min-area 50)
(def max-area 10000)

(defn- task-object?
  ([obj descriptors]
   (let [seg-area (-> obj :arguments :region reg/area)
         shape-desc (:shape-description (:arguments obj))
         size-desc (:size-description (:arguments obj))]

        (if (empty? descriptors) true
             (and (some #(= shape-desc %) descriptors)
              (some #(= size-desc %) descriptors)))))
  ([obj]
   (task-object? obj [])))

(defn- mask-segment? [seg]
  (> (-> seg :region reg/area) 100000))

(defn get-numerosity [lex-num]
  (.indexOf number-words (:lexeme (:arguments lex-num))))


(defn numerosity [lex-num source]
  {:name "number"
   :arguments {:usage "counter" :value (get-numerosity lex-num) :update-on "change"}
   :type "instance"
   :world nil
   :source source})


(defn- sweep-complete? [content]
  (let [sweep-chunk (first (filter #(= (:name %) "sweep-position") content))
        fixations (filter #(= (:name %) "fixation") content)]

    (println "sweep-complete? value: " (:value (:arguments sweep-chunk)))
        ;(println "sweep-complete? value: " (:value sweep-chunk))
    (cond

      (and (:value (:arguments sweep-chunk)) (seq fixations))
      (empty? (filter #(> (:x (reg/center (:region (:segment (:arguments %))))) (:value (:arguments sweep-chunk))) fixations))

      :else
      false)))





(defrecord NumerosityLexicalizer [buffer]
  Component
  (receive-focus
   [component focus content]
   (let [current-lex-num (first (filter #(and (= (:name %) "lexeme")
                                            (= (:world %) "phonological-buffer")) content))
         fixation-count (count (filter #(= (:name %) "fixation") content))
         inhibition-count (count (filter #(= (:name %) "fixation-inhibition") content))
         mask-segment? (some #(mask-segment? %) (obj/get-segments-persistant content))
         sweep-complete? (sweep-complete? content)
         enumeration-subset (first (filter #(= (:name %) "enumeration-subset") content))
         descriptors (if enumeration-subset (:descriptors (:arguments enumeration-subset)) [])
         ans (first (filter #(= (:name %) "number-sense") content))
         report (first (filter #(= (:name %) "number-report") content))]
    (cond

          (or (= "number-report" (:name focus)) (and (= (:name focus) "memorize") (= (:name (:element (:arguments focus))) "number-report")))
          (reset! (:buffer component) nil)

          ;; start lexical count based on subitized numerosity
          (and (= (:name focus) "vstm-enumeration") (not mask-segment?)
             (= (:type focus) "instance")
             ;; highly specialized component
             (:count (:arguments focus)))
          (reset! (:buffer component) (lexicalize-numerosity (:count (:arguments focus)) component))

           ;; if lexical count begun - prime next number
          (and (not (nil? current-lex-num)) (= (:name focus) "object") (task-object? focus descriptors) (< (get-numerosity current-lex-num) fixation-count))
          (reset! (:buffer component) (lexicalize-numerosity (inc (get-numerosity current-lex-num)) component))

          (and (not (nil? ans)) (not (nil? report)))
          (reset! (:buffer component) (lexicalize-numerosity (:value (:arguments report)) component))

          (and mask-segment? (not (nil? report)))
          (reset! (:buffer component) (lexicalize-numerosity (:value (:arguments report)) component))

          (and sweep-complete? current-lex-num)
          (reset! (:buffer component) (numerosity current-lex-num component))

          :else
          (reset! (:buffer component) nil))))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method NumerosityLexicalizer [comp ^java.io.Writer w]
  (.write w (format "NumerosityLexicalizer{}")))

(defn start []
  (NumerosityLexicalizer. (atom nil)))
