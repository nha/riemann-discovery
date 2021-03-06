(ns riemann-discovery.core-test
  (:require [riemann-discovery.core :as discovery]
            [riemann.time.controlled :refer :all]
            [riemann.time :refer [unix-time]]
            [riemann-discovery.test-utils :refer [with-mock]]
            [clojure.test :refer :all]))

(use-fixtures :once control-time!)
(use-fixtures :each reset-time!)

(deftest generate-events-test
  (is (= (vec (discovery/generate-events {["foo.bar" "kafka"] {:ttl 60}
                                          ["foobar.bar" "kafka"] {:tags []
                                                                  :ttl 60}
                                          ["baz.boo" "api"] {:tags []
                                                             :ttl 120}}
                                         "removed"))
         [{:host "foo.bar"
           :service "kafka"
           :state "removed"
           :ttl 60
           :tags ["riemann-discovery"]
           :time nil}
          {:host "foobar.bar"
           :state "removed"
           :service "kafka"
           :ttl 60
           :tags ["riemann-discovery"]
           :time nil}
          {:host "baz.boo"
           :state "removed"
           :service "api"
           :ttl 120
           :tags ["riemann-discovery"]
           :time nil}])))

(defn get-random-state
  [number]
  (reduce #(assoc %1 [(* %2 2) %2] {:time 100 :ttl 80}) {} (range number)))

(deftest reinject-events-test
  (with-mock [calls riemann.config/reinject]
    (discovery/reinject-events [{:host "foo"
                                 :service "bar"
                                 :time 1
                                 :ttl 60
                                 :tags ["kafka"]
                                 :state "added"}
                                {:host "foo"
                                 :service "baz"
                                 :time 1
                                 :ttl 60
                                 :tags ["riemann"]
                                 :state "added"}])
    (is (= (first (first @calls)))
        {:host "foo"
         :service "bar"
         :time 1
         :ttl 60
         :tags ["kafka"]
         :state "added"})
    (is (= (first (second @calls)))
        {:host "foo"
         :service "baz"
         :time 1
         :ttl 60
         :tags ["riemann"]
         :state "added"})))

(deftest get-new-state-test
  (with-mock [calls discovery/reinject-events]
    (testing "first call"
      (is (= (discovery/get-new-state
              {}
              {["foo.bar" "kafka"] {:time 10
                                    :ttl 60}
               ["foobar.bar" "kafka"] {:time 10
                                       :ttl 60}}
              {})
             {["foo.bar" "kafka"] {:time 10
                                   :ttl 60}
              ["foobar.bar" "kafka"] {:time 10
                                      :ttl 60}}))
      (is (= (vec (first (last @calls)))
             [{:host "foo.bar"
               :service "kafka"
               :state "added"
               :time 10
               :ttl 60
               :tags ["riemann-discovery"]}
              {:host "foobar.bar"
               :service "kafka"
               :state "added"
               :time 10
               :ttl 60
               :tags ["riemann-discovery"]}])))
    (testing "same configuration"
      (is (= (discovery/get-new-state
              {["foo.bar" "kafka"] {:time 10
                                    :ttl 60}
               ["foobar.bar" "kafka"] {:time 10
                                       :ttl 60}}
              {["foo.bar" "kafka"] {:time 10
                                    :ttl 60}
               ["foobar.bar" "kafka"] {:time 10
                                       :ttl 60}}
              {})
             {["foo.bar" "kafka"] {:time 10
                                   :ttl 60}
              ["foobar.bar" "kafka"] {:time 10
                                      :ttl 60}})))
    (is (= (vec (first (last @calls))) []))
    (testing "remove service"
      (is (= (discovery/get-new-state
              {["foo.bar" "kafka"] {:time 10
                                    :ttl 60}
               ["foobar.bar" "kafka"] {:time 10
                                       :ttl 60}}
              {["foo.bar" "kafka"] {:time 10
                                    :ttl 60}}
              {})
             {["foo.bar" "kafka"] {:time 10
                                   :ttl 60}})))
    (is (= (vec (first (last @calls)))
           [{:host "foobar.bar"
             :service "kafka"
             :state "removed"
             :time 10
             :ttl 60
             :tags ["riemann-discovery"]}]))
    (testing "add service"
      (is (= (discovery/get-new-state
              {["foo.bar" "kafka"] {:time 10
                                    :ttl 60}}
              {["foo.bar" "kafka"] {:time 10
                                    :ttl 60}
               ["foobar.bar" "kafka"] {:time 10
                                       :ttl 60}}
              {})
             {["foo.bar" "kafka"] {:time 10
                                   :ttl 60}
              ["foobar.bar" "kafka"] {:time 10
                                      :ttl 60}})))
    (is (= (vec (first (last @calls)))
           [{:host "foobar.bar"
             :service "kafka"
             :state "added"
             :time 10
             :ttl 60
             :tags ["riemann-discovery"]}]))
    (testing "expiration"
      (is (= (discovery/get-new-state
              {["foo.bar" "kafka"] {:time 10
                                    :ttl 60}}
              {["foo.bar" "kafka"] {:time 60
                                    :ttl 60}}
              {})
             {["foo.bar" "kafka"] {:time 10
                                   :ttl 60}}))
      (is (= (vec (first (last @calls)))
           []))
      (advance! 129)
      (is (= (discovery/get-new-state
              {["foo.bar" "kafka"] {:time 10
                                    :ttl 60}}
              {["foo.bar" "kafka"] {:time 60
                                    :ttl 60}}
              {})
             {["foo.bar" "kafka"] {:time 10
                                   :ttl 60}}))
      (is (= (vec (first (last @calls)))
           []))
      (advance! 131)
      (is (= (discovery/get-new-state
              {["foo.bar" "kafka"] {:time 10
                                    :ttl 60}}
              {["foo.bar" "kafka"] {:time 120
                                    :ttl 60}}
              {})
             {["foo.bar" "kafka"] {:time 120
                                   :ttl 60}}))
      (is (= (vec (first (last @calls)))
           [{:host "foo.bar"
             :service "kafka"
             :state "added"
             :time 120
             :ttl 60
             :tags ["riemann-discovery"]}])))))

(deftest discovery-stream-test
  (let [index (riemann.config/index)
        stream (discovery/discovery-stream index)]
    (stream {:host "foo" :service "bar" :time 1 :state "added" :tags ["riemann-discovery"]})
    (is (= (riemann.index/lookup index "foo" "bar"))
        {:host "foo" :service "bar" :time 1 :state "added" :tags ["riemann-discovery"]})
    (stream {:host "foo" :service "bar" :time 1 :state "removed" :tags ["riemann-discovery"]})
   (is (= (riemann.index/lookup index "foo" "bar")) nil)))


(deftest filtre-current-state-test
  (is (= (discovery/filter-current-state {["foo.bar" "kafka"] {:tags ["foo"]}}
                                         [])
         {["foo.bar" "kafka"] {:tags ["foo"]}}))
  (is (= (discovery/filter-current-state {["foo.bar" "kafka"] {:tags ["foo"]}}
                                         nil)
         {["foo.bar" "kafka"] {:tags ["foo"]}}))
  (is (= (discovery/filter-current-state {["foo.bar" "kafka"] {:tags ["foo"]}}
                                         ["foo"])
         {["foo.bar" "kafka"] {:tags ["foo"]}}))
  (is (= (discovery/filter-current-state {["foo.bar" "kafka"] {:tags ["foo" "bar"]}}
                                         ["foo"])
         {["foo.bar" "kafka"] {:tags ["foo" "bar"]}}))
  (is (= (discovery/filter-current-state {["foo.bar" "kafka"] {:tags ["baz"]}}
                                         ["foo"])
         {})))
