package de.mpii.microblogtrack.task;

import com.google.common.collect.Lists;
import com.twitter.hbc.ClientBuilder;
import com.twitter.hbc.core.Constants;
import com.twitter.hbc.core.endpoint.StatusesSampleEndpoint;
import com.twitter.hbc.core.processor.StringDelimitedProcessor;
import com.twitter.hbc.httpclient.BasicClient;
import com.twitter.hbc.httpclient.auth.Authentication;
import com.twitter.hbc.httpclient.auth.OAuth1;
import com.twitter.hbc.twitter4j.Twitter4jStatusClient;
import de.mpii.microblogtrack.component.core.lucene.LuceneScorer;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.map.hash.TObjectLongHashMap;
import de.mpii.microblogtrack.component.HbcT4jListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.log4j.Logger;
import twitter4j.StatusListener;

/**
 * based on com.twitter.hbc.example.Twitter4jSampleStreamExample
 *
 * @author khui
 */
public class OnlineProcessor extends Processor {
    
    static Logger logger = Logger.getLogger(OnlineProcessor.class.getName());
    
    private BasicClient client;

    /**
     * read in multiple api-keys and store in apikayConfBuilder, associating
     * with corresponding time stamp indicating the latest usage
     *
     * @param keydirectory
     * @throws IOException
     */
    private String[] readAPIKey(String keydirectory) throws IOException {
        BufferedReader br;
        String consumerKey = null, consumerSecret = null, accessToken = null, accessTokenSecret = null;
        // key-timestamp records when the key being used most latest
        br = new BufferedReader(new FileReader(new File(keydirectory,
                "key-timestamp")));
        TLongObjectHashMap<String> timestampKeyfile = new TLongObjectHashMap<>();
        TObjectLongHashMap<String> keyfileTimestamp = new TObjectLongHashMap<>();
        while (br.ready()) {
            String line = br.readLine();
            String[] cols = line.split(" ");
            if (cols.length == 2) {
                timestampKeyfile.put(Long.parseLong(cols[1]), cols[0]);
                keyfileTimestamp.put(cols[0], Long.parseLong(cols[1]));
            }
        }
        br.close();
        long[] timestamps = timestampKeyfile.keys();
        Arrays.sort(timestamps);
        String keyfile = timestampKeyfile.get(timestamps[0]);
        br = new BufferedReader(new FileReader(new File(keydirectory,
                keyfile)));
        while (br.ready()) {
            String line = br.readLine();
            String[] cols = line.split("=");
            switch (cols[0]) {
                case "oauth.consumerKey":
                    consumerKey = cols[1];
                    break;
                case "oauth.consumerSecret":
                    consumerSecret = cols[1];
                    break;
                case "oauth.accessToken":
                    accessToken = cols[1];
                    break;
                case "oauth.accessTokenSecret":
                    accessTokenSecret = cols[1];
                    break;
            }
        }
        br.close();
        try (PrintStream ps = new PrintStream(new File(keydirectory, "key-timestamp"))) {
            for (String keyname : keyfileTimestamp.keySet()) {
                if (keyname.equals(keyfile)) {
                    ps.println(keyname + " " + System.currentTimeMillis());
                    continue;
                }
                ps.println(keyname + " " + keyfileTimestamp.get(keyname));
            }
        }
        logger.info("Readin key: " + keyfile);
        return new String[]{consumerKey, consumerSecret, accessToken, accessTokenSecret};
    }
    
    @Override
    protected void receiveStatus(LuceneScorer lscorer, String keydir, int numProcessingThreads) {
        StatusListener listener = new HbcT4jListener(lscorer);
        String[] apikey = null;
        try {
            apikey = readAPIKey(keydir);
        } catch (IOException ex) {
            logger.error("", ex);
        }
        String consumerKey = apikey[0];
        String consumerSecret = apikey[1];
        String token = apikey[2];
        String secret = apikey[3];
        // Define our endpoint: By default, delimited=length is set (we need this for our processor)
        // and stall warnings are on.
        StatusesSampleEndpoint endpoint = new StatusesSampleEndpoint();
        Authentication auth = new OAuth1(consumerKey, consumerSecret, token, secret);

        // Create a new BasicClient. By default gzip is enabled.
        client = new ClientBuilder()
                .hosts(Constants.STREAM_HOST)
                .endpoint(endpoint)
                .authentication(auth)
                .processor(new StringDelimitedProcessor(api2indexqueue))
                .build();

        // Create an executor service which will spawn threads to do the actual work of parsing the incoming messages and
        // calling the listeners on each message
        ExecutorService service = Executors.newFixedThreadPool(numProcessingThreads);

        // Wrap our BasicClient with the twitter4j client
        Twitter4jStatusClient t4jClient = new Twitter4jStatusClient(
                client, api2indexqueue, Lists.newArrayList(listener), service);

        // Establish a connection
        t4jClient.connect();
        for (int threads = 0; threads < numProcessingThreads; threads++) {
            t4jClient.process();
        }
        logger.info("Connected to the API.");
    }
    
    public void close() {
        client.stop();
    }
}
