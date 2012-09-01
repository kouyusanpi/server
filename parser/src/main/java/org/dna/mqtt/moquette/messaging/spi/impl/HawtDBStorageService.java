package org.dna.mqtt.moquette.messaging.spi.impl;

import org.dna.mqtt.moquette.messaging.spi.IMatchingCondition;
import org.dna.mqtt.moquette.messaging.spi.IStorageService;
import org.dna.mqtt.moquette.messaging.spi.impl.events.CleanInFlightEvent;
import org.dna.mqtt.moquette.messaging.spi.impl.events.PublishEvent;
import org.dna.mqtt.moquette.messaging.spi.impl.events.RemoveAllSubscriptionsEvent;
import org.dna.mqtt.moquette.messaging.spi.impl.events.RepublishEvent;
import org.dna.mqtt.moquette.proto.messages.AbstractMessage;
import org.fusesource.hawtbuf.codec.StringCodec;
import org.fusesource.hawtdb.api.BTreeIndexFactory;
import org.fusesource.hawtdb.api.MultiIndexFactory;
import org.fusesource.hawtdb.api.PageFileFactory;
import org.fusesource.hawtdb.api.SortedIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.dna.mqtt.moquette.messaging.spi.impl.SimpleMessaging.StoredMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;


/**
 * Implementation of IStorageService backed by HawtDB
 */
public class HawtDBStorageService implements IStorageService {

    private static final Logger LOG = LoggerFactory.getLogger(HawtDBStorageService.class);

    private MultiIndexFactory m_multiIndexFactory;
    private PageFileFactory pageFactory;

    //maps clientID to the list of pending messages stored
    private SortedIndex<String, List<PublishEvent>> m_persistentMessageStore;
    private SortedIndex<String, StoredMessage> m_retainedStore;
    //bind clientID+MsgID -> evt message published
    private SortedIndex<String, PublishEvent> m_inflightStore;

    //persistent Map of clientID, list of Subscriptions
    //TODO rename into persistentSubscriptions
    private SortedIndex<String, List<Subscription>> m_persistent;

    public HawtDBStorageService(MultiIndexFactory multiIndexFactory) {
        m_multiIndexFactory =  multiIndexFactory;
    }


    public void initStore() {
        initRetainedStore();
        //init the message store for QoS 1/2 messages in clean sessions
        initPersistentMessageStore();
        initInflightMessageStore();
        initPersistentSubscriptions();
    }

    private void initRetainedStore() {
        BTreeIndexFactory<String, StoredMessage> indexFactory = new BTreeIndexFactory<String, StoredMessage>();
        indexFactory.setKeyCodec(StringCodec.INSTANCE);

        m_retainedStore = (SortedIndex<String, StoredMessage>) m_multiIndexFactory.openOrCreate("retained", indexFactory);

    }


    private void initPersistentMessageStore() {
        BTreeIndexFactory<String, List<PublishEvent>> indexFactory = new BTreeIndexFactory<String, List<PublishEvent>>();
        indexFactory.setKeyCodec(StringCodec.INSTANCE);

        m_persistentMessageStore = (SortedIndex<String, List<PublishEvent>>) m_multiIndexFactory.openOrCreate("persistedMessages", indexFactory);
    }

    private void initPersistentSubscriptions() {
        BTreeIndexFactory<String, List<Subscription>> indexFactory =
                new BTreeIndexFactory<String, List<Subscription>>();
        indexFactory.setKeyCodec(StringCodec.INSTANCE);

        m_persistent = (SortedIndex<String, List<Subscription>>) m_multiIndexFactory.openOrCreate("subscriptions", indexFactory);
    }

    /**
     * Initialize the message store used to handle the temporary storage of QoS 1,2
     * messages in flight.
     */
    private void initInflightMessageStore() {
        BTreeIndexFactory<String, PublishEvent> indexFactory = new BTreeIndexFactory<String, PublishEvent>();
        indexFactory.setKeyCodec(StringCodec.INSTANCE);

        m_inflightStore = (SortedIndex<String, PublishEvent>) m_multiIndexFactory.openOrCreate("inflight", indexFactory);
    }

    public void storeRetained(String topic, byte[] message, AbstractMessage.QOSType qos) {
        if (message.length == 0) {
            //clean the message from topic
            m_retainedStore.remove(topic);
        } else {
            //store the message to the topic
            m_retainedStore.put(topic, new StoredMessage(message, qos));
        }
    }

    public Collection<StoredMessage> searchMatching(IMatchingCondition condition) {
        LOG.debug("searchMatching scanning all retained messages, presents are " + m_retainedStore.size());

        List<StoredMessage> results = new ArrayList<StoredMessage>();

        for (Map.Entry<String, StoredMessage> entry : m_retainedStore) {
            StoredMessage storedMsg = entry.getValue();
            if (condition.match(entry.getKey())) {
                results.add(storedMsg);
            }
        }

        return results;
    }

    public void storePublishForFuture(PublishEvent evt) {
        List<PublishEvent> storedEvents;
        String clientID = evt.getClientID();
        if (!m_persistentMessageStore.containsKey(clientID)) {
            storedEvents = new ArrayList<PublishEvent>();
        } else {
            storedEvents = m_persistentMessageStore.get(clientID);
        }
        storedEvents.add(evt);
        m_persistentMessageStore.put(clientID, storedEvents);
    }

    public List<PublishEvent> retrivePersistedPublishes(String clientID) {
        return m_persistentMessageStore.get(clientID);
    }

    public void cleanPersistedPublishes(String clientID) {
        m_persistentMessageStore.remove(clientID);
    }

    public void cleanInFlight(String msgID) {
        m_inflightStore.remove(msgID);
    }

    public void addInFlight(PublishEvent evt, String publishKey) {
        m_inflightStore.put(publishKey, evt);
    }

    public void addNewSubscription(Subscription newSubscription, String clientID) {
        if (!m_persistent.containsKey(clientID)) {
            m_persistent.put(clientID, new ArrayList<Subscription>());
        }

        List<Subscription> subs = m_persistent.get(clientID);
        subs.add(newSubscription);
        m_persistent.put(clientID, subs);
    }

    public void removeAllSubscriptions(String clientID) {
        m_persistent.remove(clientID);
    }

    public List<Subscription> retrieveAllSubscriptions() {
        List<Subscription> allSubscriptions = new ArrayList<Subscription>();
        for (Map.Entry<String, List<Subscription>> entry : m_persistent) {
            allSubscriptions.addAll(entry.getValue());
        }
        return allSubscriptions;
    }
}
