(ns
  ^{:doc "Functions that assist in providing text-to-speech (tts) services
          to ARCADIA."}
  arcadia.utility.tts)

(def ^:private python-tts? "Has tts been loaded for python?" false)
(def ^:private use-python-tts? "Use pytts if available?" true)

;;Attempt to load the tts libraries for python, setting python-tts?
;;to true if successful.
(try
  (require '[libpython-clj2.require :refer [require-python]]
           '[libpython-clj2.python :refer [py. py.. py.-] :as py])
  ((resolve 'require-python) '[pyttsx3 :as pytts])
  ;(require-python 'pyttsx3)
  (def ^:private python-tts? true)
  (catch Exception e nil))

(defmacro wp
  "Code gets runs only if libpython-clj and the python tts library are available and python-tts selected."
  [code]
  (when (and python-tts? use-python-tts?)
    code))


(declare pytts-engine)
(wp (def pytts-engine (pytts/init)))

(defn speak [text]
    (println "utility/tts.clj Simulated TTS: '" text "'"))

(wp
  (defn speak [text]
    (py. pytts-engine "say" text)
    (py. pytts-engine "runAndWait")))

;; TEST COMMAND
;(speak "test sentence python T T S tts")
