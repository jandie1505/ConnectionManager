package net.jandie1505.connectionmanager.streams;

import net.jandie1505.connectionmanager.events.CMClientInputStreamByteLimitReachedEvent;
import net.jandie1505.connectionmanager.interfaces.StreamOwner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CMConsumingInputStream extends CMInputStream {
    private final List<Integer> queue;
    private final Thread thread;
    private long count;
    private long byteExpiration;
    private int streamByteLimit;

    public CMConsumingInputStream(StreamOwner owner) {
        super(owner);
        this.queue = Collections.synchronizedList(new ArrayList<>());
        this.byteExpiration = 1000;
        this.streamByteLimit = 2500000;

        this.thread = new Thread(() -> {
            this.count = 1000;
            while(!Thread.currentThread().isInterrupted() && !owner.isClosed()) {
                if(!this.queue.isEmpty()) {
                    if(this.queue.size() > this.streamByteLimit) {
                        this.queue.clear();
                        this.getOwner().fireEvent(new CMClientInputStreamByteLimitReachedEvent(this.getOwner().getEventClient(), this));
                    } else {
                        if(this.count > 0) {
                            this.count--;

                            // SLEEP IS NOT REQUIRED HERE BECAUSE THE ENTIRE THREAD WILL SLEEP 1 MILLISECOND
                        } else {
                            this.queue.remove(0);
                            this.count = this.byteExpiration;
                        }
                    }
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ignored) {}
                }
            }
        });
    }


    @Override
    public synchronized int read() {
        if(this.thread.isInterrupted() || this.getOwner().isClosed()) {
            return -1;
        }
        while(this.queue.isEmpty()) {
            if(this.thread.isInterrupted() || this.getOwner().isClosed()) {
                return -1;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException ignored) {}
        }
        this.count = this.byteExpiration;
        return this.queue.remove(0);
    }

    @Override
    @Deprecated
    public void send(int b) {
        this.queue.add(b);
    }

    public long getByteExpiration() {
        return this.byteExpiration;
    }

    public void setByteExpiration(long byteExpiration) {
        if(byteExpiration > 0) {
            this.byteExpiration = byteExpiration;
        } else {
            throw new IllegalArgumentException("The time must be positive");
        }
    }

    public void setStreamByteLimit(int streamByteLimit) {
        if(streamByteLimit > 0) {
            this.streamByteLimit = streamByteLimit;
        } else {
            throw new IllegalArgumentException("The limit must be positive");
        }
    }

    public int getStreamByteLimit() {
        return this.streamByteLimit;
    }
}
