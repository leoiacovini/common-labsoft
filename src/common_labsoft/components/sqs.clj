(ns common-labsoft.components.sqs
  (:require [clojure.core.async :as async]
            [amazonica.aws.sqs :as sqs]
            [cheshire.core :as cheshire]
            [io.pedestal.log :as log]
            [common-labsoft.misc :as misc]
            [common-labsoft.protocols.sqs :as protocols.sqs]
            [com.stuartsierra.component :as component]))

(defn receive-message! [queue]
  (some-> (sqs/receive-message (:url queue))
          :messages
          first
          (assoc :queue-url (:url queue))))

(defn parse-message [message]
  (-> message
      :body
      (cheshire/parse-string true)))

(defn serialize-message [message]
  (cheshire/generate-string message))

(defn fetch-message! [queue queue-channel]
  (async/go-loop []
    (some->> (receive-message! queue)
             (async/>! queue-channel))
    (recur)))

(defn handle-message! [{handler-fn :handler :as queue} queue-channel]
  (async/go-loop []
    (when-let [message (async/<! queue-channel)]
      (try
        (some-> message
                parse-message
                handler-fn)
        (sqs/delete-message message)
        (catch Throwable e
          (log/error :exception e :error :receving-message :queue queue :message message))))
    (recur)))

(defn init-async-consumer! [queue]
  (let [queue-channel (async/chan 50)]
    (fetch-message! queue queue-channel)
    (handle-message! queue queue-channel)
    (assoc queue :chan queue-channel)))

(defn stop-async-consumer! [queue]
  (some-> queue
          :chan
          async/close!)
  (dissoc! queue :chan))

(defn start-consumers! [queues]
  (misc/map-vals init-async-consumer! queues))

(defn stop-consumers! [queues]
  (misc/map-vals stop-async-consumer! queues))

(defn find-or-create-queue! [qname]
  (or (sqs/find-queue qname)
      (:queue-url (sqs/create-queue qname))))

(defn queue-config->queue [qname qconf]
  (assoc qconf :name name
               :url (find-or-create-queue! (name qname))))

(defn gen-queue-map [config-map]
  (misc/map-vals queue-config->queue config-map))

(defn produce! [queue message]
  (sqs/send-message (:url queue) (serialize-message message)))

(defrecord SQS [config queues-config]
  component/Lifecycle
  (start [this]
    (assoc this :queues (-> (gen-queue-map queues-config)
                            (start-consumers!))))

  (stop [this]
    (stop-consumers! (:queues this))
    (dissoc this :queues))

  protocols.sqs/SQS
  (produce! [this produce-map]
    (produce! (-> this :queues (get (:queue produce-map))) (:message produce-map))))