(ns scanner.acceptance
  (:require clojure.set
            clojure.pprint
            [clojure.walk :as cw]
            [incanter
             [core :as ic]
             [io :as ioc]
             [charts :as ich]])
  (:use [scanner.io :only [list-files
                           list-directories
                           save-dataset]]
        [scanner.sensitivity :only [config->string
                                    validate-root-exists
                                    has-calibration-scans
                                    get-calibration
                                    directory->dataset]]
        [clojure.java.io :only [file]]
        [clojure.string :only [join
                               split]]
        [clojure.java.shell :only [sh]]))

(def ^:const column-mapping
  {:x-rotation :gyro-x
   :y-rotation :gyro-y
   :z-rotation :gyro-z
   :x-translation :acc-x
   :y-translation :acc-y
   :z-translation :acc-z})

(defn write-out-config
  "Write the scanner configuration to a file, and return the path."
  [root-path filename offsets sensitivities]
  (let [config-file-path (str root-path filename)]
    (spit config-file-path
          (config->string offsets sensitivities))
    config-file-path))

(defn dir->test-name
  "Expects a java.io.File as directory"
  [directory]
  (first
   (split
    (.. directory
        getCanonicalFile
        getName)
    #"\.d")))

(defn file->test-name
  "Expects a java.io.File as file"
  [file]
  (.. file
      getCanonicalFile
      getName))

(defn find-test-cases
  "Returns a set of strings representing all the test-cases found.
  Use test-name->dataset to get back the actual data from the test"
  [root-path]
  (let [dir-test-cases (set
                        (map dir->test-name
                             (list-directories root-path)))
        file-test-cases (set
                         (map file->test-name
                              (list-files root-path)))]
    (clojure.set/intersection dir-test-cases
                              file-test-cases)))

(defn test-name->dataset
  "Turn a test name into the corresponding dataset."
  [test-name]
  (directory->dataset (str test-name ".d")))

(defn get-exe-for-version
  "OS aware method of getting the correct binary given a binary root
  and version"
  [version-dir]
  (let [os-name (System/getProperty "os.name")]
    (cond
     (= "Linux" os-name) (file version-dir "acceptance")
     (= "Windows" os-name) (file version-dir "acceptance.exe"))))

(defn find-test-executables
  "Get a list of executables present in the \"bin/\" subdir."
  [root-path]
  (map get-exe-for-version
       (list-directories (str root-path "bin/"))))

(defn string->dataset
  "Wrapper on incanter read-dataset to allow reading a dataset from a
  string."
  [headers string]
  (with-redefs [ic/get-input-reader
                (fn [& args] (apply clojure.java.io/reader args))]
    (ic/col-names
     (ioc/read-dataset
      (java.io.BufferedReader.
       (java.io.StringReader. string))
      :delim \space)
     headers)))

(defn run-test-case
  "Run a test with the given executable, config and dataset."
  [exe config-file dataset]
  (string->dataset
   [:timestamp :gyro-x :gyro-y :gyro-z :acc-x :acc-y :acc-z]
   (:out (sh (.getCanonicalPath exe)
             config-file
             :in (with-out-str
                   (save-dataset dataset "-"
                                 :delim " "))))))

(defn read-test-description
  "Read in the test-description map.  The binding of *ns* is for if I
  decide to go back to using a macro."
  [test-case]
  (binding [*ns* (find-ns 'scanner.acceptance)]
    (try (load-file test-case)
         (catch Exception e
           (throw (Exception. (str "Error loading test description: "
                                   test-case) e))))))

(defn get-version-from-exe
  "Extract the version number from the path to the executable.
  Expects a File object."
  [exe]
  (let [path-string (.getCanonicalPath exe)
        path-components (split path-string
                               (re-pattern
                                (java.io.File/separator)))]
    (nth path-components
         (- (count path-components) 2))))

(defn normalize-dataset
  "Rebase the timestamps in this dataset to start at 0."
  [ds]
  (let [base-time (ic/sel ds :rows 0 :cols :timestamp)]
    (ic/conj-cols
     (ic/$map (fn [timestamp]
                {:timestamp (- timestamp base-time)})
              :timestamp ds)
     (ic/sel ds :except-cols :timestamp))))

(defn mean [coll]
  (let [len (count coll)]
    (if (zero? len)
      0
      (/ (reduce + coll) len))))

(defn report-test-case-results [results]
  (clojure.pprint/pprint results))

(defn process-test-names
  "Return a seq of seq's containing: [test-name-path test-description
  test-dataset]."
  [root-path test-cases]
  (map (comp (juxt identity
                   read-test-description
                   test-name->dataset)
             (partial str root-path))
       test-cases))

(defn absolute-error
  "Get the absolute error -> expected - actual"
  [expected actual]
  ;; This if statement is the whole reason this is a function; to
  ;; avoid random NPE's when I upstream functions return null
  (if (and (number? expected)
           (number? actual))
    (ic/abs (- expected actual))
    (throw (str "Expected: " expected "actual:" actual))))

(defn relative-error
  "Get the relative error -> 100 * (absolute-error / expected)"
  [expected actual]
  (let [abs-err (absolute-error expected actual)]
    (if (= 0 expected) ;; Avoid dividing by zero
      abs-err
      (* 100 (/ abs-err expected)))))

(defn split-dataset
  "Split the dataset into three sections: before movement, during
  movement and after movement."
  [dataset start-time duration]
  (let [before-start #(< (nth % 0) start-time)
        after-end #(> (nth % 0) (+ start-time
                                   duration))
        normalized-ds (normalize-dataset dataset)
        pre-ds (ic/sel normalized-ds :filter before-start)
        test-ds (ic/sel normalized-ds :filter #(and (not (before-start %))
                                                    (not (after-end %))))
        post-ds (ic/sel normalized-ds :filter after-end)]
    [pre-ds
     test-ds
     post-ds]))

(defn gen-efn
  "Return a function to calculate the expected value at a given moment
  in time."
  [start-time duration distance]
  (if (= distance 0)
    (fn [ts] 0)
    (let [avg-vel (/ distance duration)]
      (fn [ts] (* avg-vel (- ts
                             start-time))))))

(defn last-row
  "Return the selected columns from the last row in a dataset."
  [ds columns]
  (let [last-map (last (:rows (ic/sel ds :columns columns)))]
    (vec (map #(% last-map) columns))))

(defn rms
  "Take the root-mean-squared error of a collection.  Expected values are
  generated using the efn.  efn should take a timestamp and return an
  appropriate value."
  [efn coll]
  (let [len (count coll)
        sum-squares (reduce (fn [sum [ts actual]]
                              (+ sum (ic/sq (- actual
                                               (efn ts)))))
                            0 coll)]
    (ic/sqrt (/ sum-squares len))))

(defn rms-dataset
  "Get the RMS of a single dataset column."
  [ds efn column]
  (-> (ic/$rollup (partial rms efn) [:timestamp column] [] ds)
      ;; This is an ugly dirty hack to use rollup to return a single
      ;; value.
      :rows
      first
      :timestamp))

(defn check-expectations
  "Generate the results for one portion of this test/version/device
  combo."
  [test-name version ds efn portion device-axis]
  (when (ic/dataset? ds)
    (let [check-column (column-mapping device-axis)
          [end-ts end-actual] (last-row ds
                                        [:timestamp check-column])]
      (map (partial conj [test-name version (name device-axis) portion])
           [["RMS" (rms-dataset ds efn check-column)]
            ["ABS" (ic/abs (- end-actual (efn end-ts)))]
            ["EXP" (efn end-ts)]
            ["ACT" end-actual]]))))

(defn get-axis-descriptions
  "Return a description map that is more complete.  Currently simply
  fills in missing values with 0's."
  [test-description]
  (vec (select-keys (merge {:x-rotation 0
                            :y-rotation 0
                            :z-rotation 0
                            :x-translation 0
                            :y-translation 0
                            :z-translation 0}
                           test-description)
                    [:x-rotation
                     :y-rotation
                     :z-rotation
                     :x-translation
                     :y-translation
                     :z-translation])))

(defn process-test
  "Process one test/version pair into the results for each
  device-axis/portion"
  [test-name version dataset
   {:keys [start-time duration radius]
    :as test-description}]
  (let [start-time (* 1000 start-time) ;; Convert time units to
        duration   (* 1000 duration)   ;; milliseconds
        [pre-ds ds post-ds] (split-dataset dataset
                                           start-time
                                           duration)]
    (for [[device-axis distance] (get-axis-descriptions test-description)]
      (let [efn (gen-efn start-time duration distance)
            pre-efn (fn [ts] 0)
            post-efn (fn [ts] (efn (+ start-time duration)))]
        (map (fn [[ds efn label]]
               (check-expectations test-name
                                   version
                                   ds
                                   efn
                                   label
                                   device-axis))
             [[pre-ds pre-efn "pre"]
              [ds efn "test"]
              [post-ds post-efn "post"]])))))

(defn uuid []
  (str (java.util.UUID/randomUUID)))

(defn setup-test [root-path target-path]
  (when (has-calibration-scans root-path)
   (let [ ;; Generate a config for this test
         {:keys [offsets sensitivities]} (get-calibration root-path)
         config-path (write-out-config target-path
                                       (uuid)
                                       offsets sensitivities)]
     (for [test-name (find-test-cases root-path)]
       ;; find-test-cases already validates that a description file
       ;; and PBMP directory both exist

       ;; Extract PBMP files into dataset

       ;; Write dataset to csv file (in target)

       ;; Read test description, add config path to it.
       ;; Then write description to target
       (do)
       ))))

;; (defn -main
;;   "Run the acceptance test.  Takes a single argument of a folder.
;;   This folder should contain the requisite scans for doing a
;;   sensitivity calibration and any number of identically named
;;   directory-file pairs of the form \"<file-name>\" and
;;   \"<file-name>.d\" .

;;   Each directory should contain an image set for a single scan.  The
;;   file should contain the expected outputs of the agman when run
;;   against that scan.

;;   The file acceptance.cfg will be re-created every time this runs."
;;   [root-dir]
;;   (let [root-path     (validate-root-exists root-dir)
;;         {:keys [offsets sensitivities]} (calculate root-dir)
;;         ;; Generate a config for this test
;;         config-path (write-out-config root-path offsets sensitivities)]

;;     (save-dataset
;;      (ic/dataset
;;       [:test :version :device-axis :portion :metric :value]
;;       ;; Reorganize into dataset format (seq of seqs)
;;       (partition 6
;;                  (flatten
;;                   ;; Run the combinations of version X test-case.
;;                   (for [ ;; Iterate over test-datasets


;;                         ;; and test-executables
;;                         exe (find-test-executables root-path)]
;;                     (let [ds (run-test-case exe config-path
;;                                             (normalize-dataset dataset))
;;                           test-name (last (split test-name #"/"))
;;                           version (get-version-from-exe exe)]
;;                       (process-test test-name version ds test-description))))))
;;      "-")
;;     ;; clojure.java.shell uses futures. Make sure we shut them down.
;;     (shutdown-agents)))
