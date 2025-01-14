package de.mpii.microblogtrack.component.archiver;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;
import twitter4j.TwitterObjectFactory;
import twitter4j.TwitterStream;
import twitter4j.TwitterStreamFactory;
import twitter4j.conf.ConfigurationBuilder;

/**
 *
 * @author khui
 */
public class MultiKeysListenerT4J extends MultiKeysListener {
    
    static org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(MultiKeysListenerT4J.class.getName());
    
    private TwitterStream currenttwitter;
    
    public MultiKeysListenerT4J(BlockingQueue<String> outQueue, String keydirectory) throws IOException {
        super(outQueue, keydirectory);
    }
    
    private class StatusListenerBQ implements StatusListener {
        
        private final BlockingQueue<String> outQueue;
        
        public StatusListenerBQ(BlockingQueue<String> outQueue) {
            this.outQueue = outQueue;
        }
        
        @Override
        public void onStatus(Status status) {
            String rawJSON = TwitterObjectFactory.getRawJSON(status);
            try {
                boolean isSuccess = outQueue.offer(rawJSON, 1000, TimeUnit.MILLISECONDS);
                if (!isSuccess) {
                    logger.error("Offer to queue failed: " + outQueue.size());
                }
            } catch (InterruptedException ex) {
                logger.error("", ex);
            }
        }
        
        @Override
        public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {
        }
        
        @Override
        public void onTrackLimitationNotice(int numberOfLimitedStatuses) {
        }
        
        @Override
        public void onException(Exception ex) {
            logger.error("", ex);
        }
        
        @Override
        public void onScrubGeo(long userId, long upToStatusId) {
        }
        
        @Override
        public void onStallWarning(StallWarning arg0) {
        }
    }
    
    @Override
    protected void listener(String consumerKey, String consumerSecret, String token, String secret) throws Exception {
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.setOAuthAccessToken(token);
        cb.setOAuthAccessTokenSecret(secret);
        cb.setOAuthConsumerKey(consumerKey);
        cb.setOAuthConsumerSecret(consumerSecret);
        cb.setJSONStoreEnabled(true);
        currenttwitter = new TwitterStreamFactory(cb.build())
                .getInstance();
        StatusListener statuslistener = new StatusListenerBQ(outQueue);
        currenttwitter.addListener(statuslistener);
        currenttwitter.sample();
    }
    
    @Override
    protected void keepconnecting() throws FileNotFoundException, InterruptedException, Exception {
        updateListener(apikeyTimestamp, apikayKeys);
    }
    
}
