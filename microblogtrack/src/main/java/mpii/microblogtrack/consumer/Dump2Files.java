/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package mpii.microblogtrack.consumer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.apache.log4j.Logger;

/**
 *
 * @author khui
 */
public class Dump2Files implements Callable<Void> {

    final Logger logger = Logger.getLogger(Dump2Files.class);

    private final BlockingQueue<String> bqueue;

    private final String outdir;

    private final long TIMEOUT = 300;

    public Dump2Files(final BlockingQueue<String> bqueue, final String outdir) {
        this.bqueue = bqueue;
        this.outdir = outdir;
    }

    private String filename() {
        DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHH");
        Date date = new Date();
        return dateFormat.format(date) + ".gzip";
    }

    @Override
    public Void call() throws Exception {
        long tweetcount = 0;
        String filename = "";
        PrintStream ps = new PrintStream(new File(outdir, "outstat.log"));
        BufferedWriter writer = null;
        try {
            while (true) {
                if (tweetcount % 200000 == 0) {
                    if (writer != null) {
                        writer.close();
                        ps.println(filename + ":" + tweetcount);
                        ps.flush();
                    }
                    filename = filename();
                    writer = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                            new FileOutputStream(new File(outdir, filename), true))));
                }
                String tweet = bqueue.poll(TIMEOUT, TimeUnit.SECONDS);
                if (tweet == null) {
                    logger.error("Get no tweet within " + TIMEOUT + " seconds");
                }
                writer.write(tweet);
                writer.newLine();
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
            ps.close();
        }
    }
}
