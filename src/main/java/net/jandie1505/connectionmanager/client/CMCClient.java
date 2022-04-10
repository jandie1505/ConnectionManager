package net.jandie1505.connectionmanager.client;

import net.jandie1505.connectionmanager.client.actions.CMCAction;
import net.jandie1505.connectionmanager.client.events.CMCClosedEvent;
import net.jandie1505.connectionmanager.client.events.CMCCreatedEvent;
import net.jandie1505.connectionmanager.client.events.CMCEvent;
import net.jandie1505.connectionmanager.server.CMSClientEventListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class CMCClient {
    private Socket socket;
    private List<CMCEventListener> listeners;
    private List<CMCAction> actions;

    public CMCClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.listeners = new ArrayList<>();
        this.actions = new ArrayList<>();

        new Thread(() -> {
            while(!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
                try {
                    if(socket.getInputStream().read() == -1) {
                        close();
                    }

                    if(socket.isClosed()) {

                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        new Thread(() -> {
            while(!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
                try {
                    for(CMCAction action : actions) {
                        action.run(this);
                    }
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

        this.fireEvent(new CMCCreatedEvent(this));
    }

    /**
     * Add an EventListener
     * @param listener CMEventListener
     */
    public void addEventListener(CMCEventListener listener) {
        this.listeners.add(listener);
    }

    /**
     * Remove an EventListener
     * @param listener CMEventListener
     */
    public void removeEventListener(CMSClientEventListener listener) {
        this.listeners.remove(listener);
    }

    /**
     * Close the connections and Input/Output streams
     * @throws IOException IOException
     */
    public void close() throws IOException {
        this.fireEvent(new CMCClosedEvent(this));
        this.socket.close();
    }

    /**
     * Get the InputStream
     * @return InputStream
     * @throws IOException IOException
     */
    public InputStream getInputStream() throws IOException {
        return this.socket.getInputStream();
    }

    /**
     * Get the OutputStream
     * @return OutputStream
     * @throws IOException IOException
     */
    public OutputStream getOutputStream() throws IOException {
        return this.socket.getOutputStream();
    }

    /**
     * Get the closed and connection state
     * @return
     */
    public boolean isClosed() {
        return this.socket.isClosed();
    }

    /**
     * Get the IP Address
     * @return InetAddress
     */
    public InetAddress getIP() {
        return this.socket.getInetAddress();
    }

    /**
     * Get the port
     * @return Port
     */
    public int getPort() {
        return this.socket.getPort();
    }

    /**
     * Get socket
     * @return Socket
     * @deprecated use other methods if possible
     */
    @Deprecated
    public Socket getSocket() {
        return this.socket;
    }

    /**
     * Fire an event to all event listeners
     * @param event Event
     */
    public void fireEvent(CMCEvent event) {
        for(CMCEventListener listener : this.listeners) {
            listener.onEvent(event);
        }
    }
}
