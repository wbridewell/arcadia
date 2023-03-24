(ns arcadia.component.reporter.numerosity
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            (arcadia.utility [objects :as obj]
                             [descriptors :as d])
            [arcadia.utility.geometry :as geo]
            [clojure.data.generators :as dgen])
  (:import [org.uncommons.maths.random MersenneTwisterRNG]))

(def ^:parameter log-report? false)
(def ^:parameter log-file "enumeration-data.txt")

(def mersenne-rng (MersenneTwisterRNG.))

(def pattern-threshold 0.6)

(defn report-numerosity [number]
  {:name "number-report"
   :arguments {:value number}
   :type "instance"
   :world nil})

(defn report-numerosity-subset [number description]
  {:name "number-report"
   :arguments {:value number :descriptors description}
   :type "instance"
   :world nil})

(defn- sample-normal [n w]
  (println (str "sample-normal; n = " n))
  (let [rng mersenne-rng]
    (Math/round (+ (float n) (* (.nextGaussian rng) (float w) (float n))))))

(defn- pattern-recognition-result [result]
  (let [results (:results (:arguments result))
        numbers (mapv #(:number %) (filter #(> (:probability %) pattern-threshold) results))]
    (println (str "PATTERN-REC-RESULT results = " results))
    (println (str "PATTERN-REC-RESULT NUMBERS = " numbers))
    (if (empty? numbers) nil
        (report-numerosity (dgen/rand-nth numbers)))))

(defn- guess [explicit-count number-sense]
  (let [c (or (:count (:arguments explicit-count)) (:value (:arguments explicit-count)))
        e (:mean (:arguments number-sense))
        w (:weberfrac (:arguments number-sense))]

    (println (str "c = " c "; e = " e "; w = " w))
    (cond

      (and c (= (int c) (int e)))
      (int c)

      (and c e)
      (+ c (sample-normal (- e c) w))

      :else
      (sample-normal e w))))

(defn- mask-object? [object content]
  (when-let [reg (and object (obj/get-region object content))]
    (> (geo/area reg) 100000)))

(defn- sweep-complete? [content]
  (let [sweep-chunk (first (filter #(= (:name %) "sweep-position") content))
        fixations (filter #(= (:name %) "fixation") content)]

    (println "sweep-complete? value: " (:value (:arguments sweep-chunk)))
    (cond
      (and (:value (:arguments sweep-chunk)) (seq fixations))
      (empty? (filter #(> (:x (geo/center (:region (:segment (:arguments %))))) (:value (:arguments sweep-chunk))) fixations))

      :else
      false)))

(defrecord NumerosityReporter [buffer parameters]
  Component
  (receive-focus
    [component focus content]
   ;; NOTE:
   ;; this is the sort of thing that we might want to hang around for a few
   ;; cycles. perhaps some components should broadcast repeatedly for awhile
   ;; before dropping their buffered content?
    (let [ans (first (filter #(= (:name %) "number-sense") content))
          pattern-result (first (filter #(and (= (:world %) "working-memory") (= (:name %) "number-sense-pattern")) content))
          explicit-count (first (filter #(= (:name %) "number") content))
          current-lex-num (first (filter #(and (= (:name %) "lexeme")
                                               (= (:world %) "phonological-buffer")) content))
          cycle-count (first (filter #(= (:name %) "cycle-count") content))
          objects (obj/get-vstm-objects content)
          segments (obj/get-segments-persistant content)
          mask-present? (first (filter #(mask-object? % content) objects))
          sweep-complete? (sweep-complete? content)
          vstm-enum (first (filter #(= (:name %) "vstm-enumeration") content))
          enumeration-subset (first (filter #(= (:name %) "enumeration-subset") content))
          descriptors (if enumeration-subset (:descriptors (:arguments enumeration-subset)) [])]

      (println "sweep-complete? " sweep-complete?)
      (when mask-present? (println "MASK PRESENT!"))

      (when (-> component :parameters :log-report?)
        (let [number (d/rand-element content :name "number-report")
              cycle-tracker (d/first-element content :name "cycle-count")]
          (println "NUMBER = " (:value (:arguments number)))
          (when (not (:ans-reported? (:arguments cycle-tracker)))
            (spit (-> component :parameters :log-file)
                  (str (:value (:arguments number)) ", " (:count (:arguments cycle-tracker)))
                  :append true))))

      (cond

        (and mask-present? ans explicit-count)
        (reset! (:buffer component) (report-numerosity (guess explicit-count ans)))

        (and mask-present? ans vstm-enum)
        (reset! (:buffer component) (report-numerosity (guess vstm-enum ans)))

        (and mask-present? (= (:name focus) "vstm-enumeration"))
        (reset! (:buffer component) (report-numerosity (:count (:arguments vstm-enum))))

        (and sweep-complete? explicit-count)
        (reset! (:buffer component) (report-numerosity-subset (:value (:arguments explicit-count)) descriptors))

        pattern-result
        (reset! (:buffer component) (pattern-recognition-result pattern-result))

        :else
        (reset! (:buffer component) nil))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method NumerosityReporter [comp ^java.io.Writer w]
  (.write w (format "NumerosityReporter{}")))

(defn start [& {:as args}]
  (NumerosityReporter. (atom nil) (merge-parameters args)))
